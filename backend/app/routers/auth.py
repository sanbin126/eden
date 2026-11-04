"""账号体系路由：验证码 / 注册 / 登录 / 重置与修改密码（/api/v1/auth）。"""

import secrets
import time
from typing import Optional

from fastapi import APIRouter, Header
from fastapi.concurrency import run_in_threadpool

from ..config import CODE_RESEND_INTERVAL_SECONDS, CODE_TTL_SECONDS, logger
from ..models import (
    AuthResponse,
    ChangePasswordRequest,
    CodeLoginRequest,
    LoginRequest,
    OkResponse,
    RegisterRequest,
    ResetPasswordRequest,
    SendCodeData,
    SendCodeRequest,
    SendCodeResponse,
)
from ..security import (
    build_auth_data,
    consume_code,
    is_valid_email,
    issue_token,
    normalize_email,
    require_session,
    send_verification_email,
)
from ..store import (
    _codes,
    _db_conn,
    _store_lock,
    db_get_user_by_email,
    set_user_password,
    upsert_user,
    verify_password,
)

router = APIRouter(prefix="/api/v1/auth")


# ==================== 账号体系接口 ====================
@router.post(
    "/email/code",
    response_model=SendCodeResponse,
    summary="发送邮箱验证码",
)
async def send_email_code(req: SendCodeRequest) -> SendCodeResponse:
    """
    生成 6 位数字验证码，存入内存并用 QQ 邮箱发送到目标邮箱。

    业务失败统一返回 HTTP 200 + code!=0，客户端直接把 message 提示给用户。
    """
    email = normalize_email(req.email)
    if not is_valid_email(email):
        return SendCodeResponse(code=1, message="邮箱格式不正确")

    now = time.time()
    with _store_lock:
        last = _codes.get(email)
        if last is not None:
            elapsed = now - last["sent_at"]
            if elapsed < CODE_RESEND_INTERVAL_SECONDS:
                wait = int(CODE_RESEND_INTERVAL_SECONDS - elapsed) + 1
                return SendCodeResponse(code=1, message=f"发送过于频繁，请 {wait} 秒后重试")

    code = f"{secrets.randbelow(1_000_000):06d}"
    try:
        # smtplib 是阻塞的，丢到线程池里执行，避免卡住事件循环
        await run_in_threadpool(send_verification_email, email, code)
    except Exception as exc:  # noqa: BLE001 —— 发送失败原因多样，统一记日志并回友好提示
        logger.error("[验证码] 发送失败 email=%s：%s", email, exc)
        return SendCodeResponse(code=1, message="验证码发送失败，请稍后重试")

    with _store_lock:
        _codes[email] = {
            "code": code,
            "expire_at": now + CODE_TTL_SECONDS,
            "sent_at": now,
        }

    # 本地联调时方便直接从日志取码；上线前建议去掉这行。
    logger.info("[验证码] 已发送 email=%s scene=%s code=%s", email, req.scene, code)
    return SendCodeResponse(
        code=0, message="ok", data=SendCodeData(expireIn=CODE_TTL_SECONDS)
    )


@router.post("/register", response_model=AuthResponse, summary="注册（邮箱 + 验证码）")
async def register(req: RegisterRequest) -> AuthResponse:
    """
    校验邮箱验证码，通过后创建账号并签发 JWT 登录 token。

    账号主体（email + 密码哈希）持久化到 SQLite，卸载重装 App 后仍可用同一账号登录。
    """
    email = normalize_email(req.email)
    if not is_valid_email(email):
        return AuthResponse(code=1, message="邮箱格式不正确")

    password = req.password
    if password is not None and len(password) < 6:
        return AuthResponse(code=1, message="密码至少 6 位")

    if not consume_code(email, req.code.strip()):
        return AuthResponse(code=1, message="验证码错误或已失效")

    user = upsert_user(email, password)
    token = issue_token(email, user["user_id"])
    logger.info("[注册] 成功 email=%s password_set=%s", email, bool(password))
    return AuthResponse(code=0, message="ok", data=build_auth_data(token, user))


@router.post("/login", response_model=AuthResponse, summary="密码登录")
async def login(req: LoginRequest) -> AuthResponse:
    """校验邮箱 + 密码，成功则签发新的登录 token。"""
    email = normalize_email(req.email)
    if not is_valid_email(email):
        return AuthResponse(code=1, message="邮箱格式不正确")

    user = db_get_user_by_email(email)
    if user is None or not user.get("password_hash"):
        return AuthResponse(code=1, message="账号不存在或未设置密码，请先注册")
    if not verify_password(req.password, user["salt"], user["password_hash"]):
        return AuthResponse(code=1, message="邮箱或密码错误")

    token = issue_token(email, user["user_id"])
    logger.info("[登录] 成功 email=%s", email)
    return AuthResponse(code=0, message="ok", data=build_auth_data(token, user))


@router.post("/login/code", response_model=AuthResponse, summary="验证码登录")
async def login_by_code(req: CodeLoginRequest) -> AuthResponse:
    """
    用一个有效验证码换登录态。若该邮箱尚未注册，则自动建号（免密码），
    这样用户第一次用验证码登录也能直接进入 App。
    """
    email = normalize_email(req.email)
    if not is_valid_email(email):
        return AuthResponse(code=1, message="邮箱格式不正确")

    if not consume_code(email, req.code.strip()):
        return AuthResponse(code=1, message="验证码错误或已失效")

    user = upsert_user(email, None)
    token = issue_token(email, user["user_id"])
    logger.info("[验证码登录] 成功 email=%s", email)
    return AuthResponse(code=0, message="ok", data=build_auth_data(token, user))


@router.post(
    "/password/reset", response_model=OkResponse, summary="忘记密码（重置密码）"
)
async def reset_password(req: ResetPasswordRequest) -> OkResponse:
    """邮箱 + 验证码校验通过后，把账号密码重置为新密码；账号不存在则提示先注册。"""
    email = normalize_email(req.email)
    if not is_valid_email(email):
        return OkResponse(code=1, message="邮箱格式不正确")

    with _store_lock:
        registered = _db_conn.execute(
            "SELECT 1 FROM users WHERE email = ?", (email,)
        ).fetchone() is not None
    if not registered:
        return OkResponse(code=1, message="该邮箱尚未注册，请先注册")

    if not consume_code(email, req.code.strip()):
        return OkResponse(code=1, message="验证码错误或已失效")

    upsert_user(email, req.newPassword)
    logger.info("[重置密码] 成功 email=%s", email)
    return OkResponse(code=0, message="密码已重置，请使用新密码登录")


@router.post(
    "/password/change", response_model=OkResponse, summary="修改密码（需登录态）"
)
async def change_password(
    req: ChangePasswordRequest, authorization: Optional[str] = Header(default=None)
) -> OkResponse:
    """校验旧密码后设置新密码，需要携带 Bearer 登录凭证。"""
    user = require_session(authorization)

    if not user.get("password_hash"):
        return OkResponse(code=1, message="当前账号未设置密码，请先用验证码登录")
    if not verify_password(req.oldPassword, user["salt"], user["password_hash"]):
        return OkResponse(code=1, message="当前密码不正确")

    set_user_password(user["email"], req.newPassword)

    logger.info("[修改密码] 成功 email=%s", user["email"])
    return OkResponse(code=0, message="密码修改成功")
