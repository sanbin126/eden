"""安全相关：邮箱校验、验证码校验、验证码邮件、JWT 与登录态校验（密码哈希见 store.py）。"""

import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import smtplib
import time
from email.message import EmailMessage
from email.utils import formataddr
from pathlib import Path
from typing import Optional

from fastapi import HTTPException

from .config import (
    CODE_TTL_SECONDS,
    DB_PATH,
    EMAIL_PATTERN,
    JWT_ALGORITHM,
    JWT_EXPIRE_DAYS,
    SMTP_AUTH_CODE,
    SMTP_HOST,
    SMTP_PORT,
    SMTP_SENDER_NAME,
    SMTP_USER,
    logger,
)
from .models import AuthData, UserInfo
from .store import _codes, _store_lock, db_get_user_by_id

# ---------- 持久化 & 登录态（JWT） ----------


def _load_jwt_secret() -> str:
    """获取 JWT 签名密钥：优先环境变量，其次本地文件，最后兜底进程内随机值。"""
    env_secret = os.getenv("JWT_SECRET", "").strip()
    if env_secret:
        return env_secret
    secret_file = Path(DB_PATH).resolve().with_name(".jwt_secret")
    try:
        if secret_file.is_file():
            value = secret_file.read_text(encoding="utf-8").strip()
            if value:
                return value
        value = secrets.token_urlsafe(48)
        secret_file.write_text(value, encoding="utf-8")
        return value
    except OSError:
        # 磁盘不可写时退回进程内随机密钥：服务重启后旧 token 失效，需要重新登录。
        logger.warning("[配置] 无法读写 JWT 密钥文件，改用进程内随机密钥，重启后需重新登录")
        return secrets.token_urlsafe(48)


JWT_SECRET: str = _load_jwt_secret()


# ---------- 账号体系工具函数 ----------
def normalize_email(email: str) -> str:
    """统一小写去空格，避免同一个人用大小写注册出两个账号。"""
    return email.strip().lower()


def is_valid_email(email: str) -> bool:
    return re.match(EMAIL_PATTERN, email) is not None


def send_verification_email(to_email: str, code: str) -> None:
    """
    通过 QQ 邮箱 SMTP（SSL 465）发送验证码邮件。

    这是阻塞调用（smtplib），调用方需用 run_in_threadpool 包一层，
    避免堵住 FastAPI 的事件循环。
    """
    if not SMTP_USER or not SMTP_AUTH_CODE:
        raise RuntimeError("服务端未配置 SMTP_USER / SMTP_AUTH_CODE")

    minutes = max(1, CODE_TTL_SECONDS // 60)

    message = EmailMessage()
    message["From"] = formataddr((SMTP_SENDER_NAME, SMTP_USER))
    message["To"] = to_email
    message["Subject"] = "唔…有东西要交给你"
    message.set_content(
        "那个，打扰你了。\n\n"
        f"平台说，如果你想要登录，会需要这一串数字： {code}\n"
        f"它 {minutes} 分钟之内有效，超时之后就会失效，得重新获取。\n\n"
        "呃……这个数字，只能交给你一个人。\n"
        "千万不要随便发给别人，可以吗？\n\n"
        "如果并不是你本人在尝试登录的话，不用做任何操作，忽略这封信就好了。\n\n"
        "——三玖\n\n"
        "本邮件由Eden平台自动发送，请勿直接回复本邮件。"
    )
    message.add_alternative(
        "<html><body style=\"font-family:-apple-system,'Segoe UI','Microsoft YaHei',sans-serif;"
        "color:#333;line-height:1.8;\">"
        "<p>那个，打扰你了。</p>"
        "<p>平台说，如果你想要登录，会需要这一串数字："
        f"<strong style=\"font-size:18px;letter-spacing:2px;\">{code}</strong><br>"
        f"它 {minutes} 分钟之内有效，超时之后就会失效，得重新获取。</p>"
        "<p>呃……这个数字，只能交给你一个人。<br>"
        "千万不要随便发给别人，可以吗？</p>"
        "<p>如果并不是你本人在尝试登录的话，不用做任何操作，忽略这封信就好了。</p>"
        "<p>——三玖</p>"
        "<p style=\"margin-top:24px;font-size:12px;color:#b0b0b0;\">"
        "本邮件由Eden平台自动发送，请勿直接回复本邮件。</p>"
        "</body></html>",
        subtype="html",
    )

    with smtplib.SMTP_SSL(SMTP_HOST, SMTP_PORT, timeout=15) as server:
        server.login(SMTP_USER, SMTP_AUTH_CODE)
        server.send_message(message)


def consume_code(email: str, code: str) -> bool:
    """校验验证码；校验通过后立即作废（一次性使用），返回 True 表示通过。"""
    if not code:
        return False
    with _store_lock:
        record = _codes.get(email)
        if record is None or record["expire_at"] < time.time():
            return False
        if not secrets.compare_digest(record["code"], code):
            return False
        _codes.pop(email, None)
        return True


# ---------- JWT（HS256，标准库实现，不引入额外依赖） ----------
def _b64url_encode(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def _b64url_decode(data: str) -> bytes:
    padding = "=" * (-len(data) % 4)
    return base64.urlsafe_b64decode(data + padding)


def create_access_token(user_id: int, email: str) -> str:
    """签发 JWT：sub=用户 id，附带 email，含签发时间与过期时间。"""
    now = int(time.time())
    header = {"alg": JWT_ALGORITHM, "typ": "JWT"}
    payload = {
        "sub": user_id,
        "email": email,
        "iat": now,
        "exp": now + JWT_EXPIRE_DAYS * 86400,
    }
    segments = "{}.{}".format(
        _b64url_encode(json.dumps(header, separators=(",", ":")).encode("utf-8")),
        _b64url_encode(json.dumps(payload, separators=(",", ":")).encode("utf-8")),
    )
    signature = hmac.new(JWT_SECRET.encode("utf-8"), segments.encode("ascii"), hashlib.sha256).digest()
    return f"{segments}.{_b64url_encode(signature)}"


def decode_access_token(token: str) -> Optional[dict]:
    """校验签名与有效期，成功返回 payload，失败返回 None。"""
    try:
        segments, signature = token.rsplit(".", 1)
        expected = _b64url_encode(
            hmac.new(JWT_SECRET.encode("utf-8"), segments.encode("ascii"), hashlib.sha256).digest()
        )
        if not secrets.compare_digest(signature, expected):
            return None
        _, payload_b64 = segments.split(".")
        payload = json.loads(_b64url_decode(payload_b64))
        if int(payload.get("exp", 0)) < int(time.time()):
            return None
        return payload
    except (ValueError, KeyError, TypeError, json.JSONDecodeError):
        return None


def issue_token(email: str, user_id: int) -> str:
    """签发 JWT 登录凭证，供客户端后续带在 Authorization 头里。"""
    return create_access_token(user_id, email)


def build_auth_data(token: str, user: dict) -> AuthData:
    return AuthData(
        token=token,
        user=UserInfo(
            id=user["user_id"],
            email=user["email"],
            nickname=user.get("nickname"),
        ),
    )


def require_session(authorization: Optional[str]) -> dict:
    """
    从 `Authorization: Bearer <token>` 解析 JWT，返回对应用户记录；无效就抛 401。

    客户端把非 /auth/ 路径的 401 视为登录态失效并跳回登录页，因此这里用标准 401。
    """
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="缺少登录凭证")
    token = authorization[len("Bearer "):].strip()
    payload = decode_access_token(token)
    if payload is None:
        raise HTTPException(status_code=401, detail="登录凭证无效或已过期")
    user = db_get_user_by_id(int(payload.get("sub", 0)))
    if user is None:
        # 账号已被删除（token 仍在有效期内）同样按登录态失效处理。
        raise HTTPException(status_code=401, detail="登录凭证无效或已过期")
    return user
