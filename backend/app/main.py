"""
Eden AI 代理后端（Android 客户端 <-> 大模型 之间的中转服务）。

设计目标
--------
1. 前端与后端分离：Android 端只请求本服务，不再直连大模型；
   除 `POST /api/chat` 外，还提供账号体系接口（邮箱验证码 / 注册 / 登录）。
2. 密钥不落地到移动端：大模型 API Key 只存在于本服务的环境变量里，
   客户端即使被反编译也拿不到密钥。
3. 返回结构统一：与 App 的账号接口保持同一套响应信封
   `{"code": 0, "message": "ok", "data": {...}}`，方便 Android 端统一解析。
4. 非流式优先：默认 `stream=false`，一次性返回完整文本，方便安卓端调试；
   也支持 `stream=true` 走 SSE 流式返回。

启动方式（本地开发 / 腾讯云 CloudBase 云托管）
--------------------------------------------
本地开发：
    pip install -r requirements.txt
    copy .env.example .env          # 填入 ARK_* 与 SMTP_* 配置
    uvicorn app.main:app --host 0.0.0.0 --port 80

云托管（CloudBase Run）：
    容器内部端口固定 80，启动命令见 Dockerfile：
    uvicorn app.main:app --host 0.0.0.0 --port 80
    注意：线上严禁使用 --reload（会在容器内反复重启导致服务不稳定）。

文档地址：http://127.0.0.1:80/docs  （云托管上用平台分配的自带测试域名访问 /docs）
"""

import asyncio
import base64
import hashlib
import hmac
import json
import logging
import os
import re
import secrets
import smtplib
import sqlite3
import threading
import time
from email.message import EmailMessage
from email.utils import formataddr
from pathlib import Path
from typing import AsyncIterator, Dict, List, Literal, Optional, Tuple

import httpx
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, Field

# 读取同目录下的 .env。这里用 __file__ 拼出绝对路径，而不是 load_dotenv() 默认的
# 「按调用栈向上查找 + 依赖当前工作目录」——否则从别的目录启动服务时会找不到 .env。
ENV_PATH = Path(__file__).resolve().with_name(".env")

try:
    from dotenv import load_dotenv
except ImportError as exc:
    # 缺少依赖时给出明确指引，避免只看到一句让人摸不着头脑的 ModuleNotFoundError。
    raise RuntimeError(
        f"缺少 python-dotenv 依赖，无法读取 {ENV_PATH}。"
        "请先执行：pip install -r requirements.txt"
    ) from exc

if ENV_PATH.is_file():
    # override=False：已存在的真实环境变量优先，方便用 `$env:ARK_API_KEY=...` 临时覆盖 .env。
    load_dotenv(dotenv_path=ENV_PATH, override=False)

# ==================== 日志 ====================
# 需求：打印请求时间、用户提问内容，便于排查线上问题。
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s - %(message)s",
)
logger = logging.getLogger("eden.ai")


# ==================== 配置（全部来自环境变量） ====================
# 大模型 API Key —— 这是唯一保存密钥的地方，绝不返回给客户端。
ARK_API_KEY: str = os.getenv("ARK_API_KEY", "")

# 大模型 OpenAI 兼容接口地址（火山方舟默认值）。这里统一补上结尾的 "/"。
ARK_BASE_URL: str = os.getenv(
    "ARK_BASE_URL", "https://ark.cn-beijing.volces.com/api/v3/"
).rstrip("/") + "/"

# 模型名 / 推理接入点 ID，例如 doubao-pro-32k 或 ep-2024xxxx
ARK_MODEL_NAME: str = os.getenv("ARK_MODEL_NAME", "")

# 可选的简单请求校验：设置后，客户端必须带 `Authorization: Bearer <APP_TOKEN>`。
# 留空表示不校验（方便本地联调）。
APP_TOKEN: str = os.getenv("APP_TOKEN", "")

# 单次请求超时时间（秒）。大模型首字延迟可能较高，默认给到 60 秒。
REQUEST_TIMEOUT: float = float(os.getenv("REQUEST_TIMEOUT", "60"))


def _mask(secret: str) -> str:
    """日志里只暴露密钥首尾，避免把整串密钥写进日志文件。"""
    if not secret:
        return "(空)"
    if len(secret) <= 8:
        return secret[0] + "***"
    return f"{secret[:4]}***{secret[-4:]}"


# 启动时打印配置读取结果：key_configured 为 false 时，一眼就能定位是哪一项没读到。
logger.info("[配置] .env 路径=%s 文件存在=%s", ENV_PATH, ENV_PATH.is_file())
logger.info(
    "[配置] ARK_API_KEY=%s ARK_MODEL_NAME=%s ARK_BASE_URL=%s",
    _mask(ARK_API_KEY),
    ARK_MODEL_NAME or "(空)",
    ARK_BASE_URL,
)
if not ARK_API_KEY or not ARK_MODEL_NAME:
    logger.error(
        "[配置] 大模型配置缺失，/api/health 将返回 key_configured=false。"
        "请检查 %s 中的 ARK_API_KEY / ARK_MODEL_NAME，"
        "并确认启动服务所用的 Python 已安装 python-dotenv。",
        ENV_PATH,
    )

# ==================== 账号体系配置（邮箱验证码 / 注册 / 登录） ====================
# SMTP：QQ 邮箱默认 smtp.qq.com:465（SSL），授权码在邮箱「设置 - 账户」中生成，
# 不是 QQ 登录密码。授权码只存在于服务端 .env，绝不下发给客户端。
SMTP_HOST: str = os.getenv("SMTP_HOST", "smtp.qq.com")
SMTP_PORT: int = int(os.getenv("SMTP_PORT", "465"))
SMTP_USER: str = os.getenv("SMTP_USER", "")
SMTP_AUTH_CODE: str = os.getenv("SMTP_AUTH_CODE", "")
SMTP_SENDER_NAME: str = os.getenv("SMTP_SENDER_NAME", "中野三玖")

# 验证码有效期（秒）与同一邮箱的重发冷却时间（秒）
CODE_TTL_SECONDS: int = int(os.getenv("CODE_TTL_SECONDS", "300"))
CODE_RESEND_INTERVAL_SECONDS: int = int(os.getenv("CODE_RESEND_INTERVAL_SECONDS", "60"))

# 邮箱格式校验（够用即可，不追求 RFC 完备）
EMAIL_PATTERN = r"^[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}$"

# 密码哈希迭代次数（PBKDF2-HMAC-SHA256）
PASSWORD_HASH_ITERATIONS = 100_000

# ---------- 持久化 & 登录态（JWT）配置 ----------
# 账号主体（email / 密码哈希）与业务数据都写入 SQLite，服务重启后依然存在；
# 卸载重装 App 只会丢本地缓存的 token，重新登录即可，账号本体不受影响。
DB_PATH: str = os.getenv("DB_PATH") or str(Path(__file__).resolve().with_name("eden.db"))

# JWT 签名密钥与有效期。密钥优先取环境变量；未配置时自动生成并落盘到
# .jwt_secret，保证服务重启后已签发的 token 依旧有效。
JWT_EXPIRE_DAYS: int = int(os.getenv("JWT_EXPIRE_DAYS", "30"))
JWT_ALGORITHM = "HS256"

# 验证码是短时效数据，仍放内存即可（重启后重新获取验证码，不影响账号）。
# email -> {"code", "expire_at", "sent_at"}
_codes: Dict[str, dict] = {}
# 并发读写保护：验证码 + SQLite 连接（sqlite3 连接本身非线程安全，统一串行化）
_store_lock = threading.Lock()


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

# ==================== SQLite 持久化 ====================
# 连接在模块加载时建立一次，配合 _store_lock 串行访问，避免多线程竞争。
_db_conn = sqlite3.connect(DB_PATH, check_same_thread=False)
_db_conn.row_factory = sqlite3.Row


def _init_db() -> None:
    """建表：users（账号主体）与 user_data（按用户隔离的业务数据）。"""
    with _store_lock:
        _db_conn.execute("PRAGMA journal_mode=WAL")
        _db_conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS users (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                email         TEXT    NOT NULL UNIQUE,
                nickname      TEXT,
                salt          TEXT,
                password_hash TEXT,
                created_at    INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS user_data (
                user_id    INTEGER NOT NULL,
                data_key   TEXT    NOT NULL,
                value      TEXT    NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (user_id, data_key)
            );
            """
        )
        _db_conn.commit()


_init_db()
logger.info("[配置] 数据库=%s JWT 有效期=%d 天", DB_PATH, JWT_EXPIRE_DAYS)

# 单条消息与整体对话长度上限，用于最基础的请求校验，防止恶意超长请求。
MAX_CONTENT_LENGTH = 4000
MAX_MESSAGES = 80

# 服务监听端口（仅作记录，实际端口由启动命令的 --port 决定）
# 云托管容器内部端口固定为 80，故本地与线上统一使用 80。
DEFAULT_PORT = int(os.getenv("PORT", "80"))

# 上游大模型调用失败时的重试策略：500 / 超时自动重试，最多 3 次，间隔指数退避。
UPSTREAM_MAX_RETRIES = int(os.getenv("UPSTREAM_MAX_RETRIES", "3"))
UPSTREAM_RETRY_BASE_DELAY = float(os.getenv("UPSTREAM_RETRY_BASE_DELAY", "1.0"))

# ==================== 配置：联网搜索（可选能力） ====================
# 目的：让模型在回答「现实世界的事实/即时信息」时先联网检索，而不是凭记忆编造。
# 策略：可配置服务商 + 未配置 Key 时自动关闭。SEARCH_API_KEY 为空时整个搜索能力
# 关闭，行为与未接入前完全一致，不影响既有部署。当前默认适配「博查 BochaAI」，
# 如需换服务商，只需改写下方 run_web_search() 一个函数。
SEARCH_PROVIDER: str = os.getenv("SEARCH_PROVIDER", "bocha").strip().lower()
SEARCH_API_KEY: str = os.getenv("SEARCH_API_KEY", "")
SEARCH_BASE_URL: str = os.getenv(
    "SEARCH_BASE_URL", "https://api.bochaai.com/v1/web-search"
)
SEARCH_MAX_RESULTS: int = int(os.getenv("SEARCH_MAX_RESULTS", "5"))
SEARCH_TIMEOUT: float = float(os.getenv("SEARCH_TIMEOUT", "15"))
# 工具调用最大轮数，防止模型陷入无限「搜索 → 再搜索」循环。
SEARCH_MAX_ROUNDS: int = int(os.getenv("SEARCH_MAX_ROUNDS", "2"))


def search_enabled() -> bool:
    """仅在配置了搜索 API Key 时启用联网能力，否则完全关闭。"""
    return bool(SEARCH_API_KEY)


# 联网搜索配置结果：未配置 Key 时明确提示「联网已关闭」，避免误以为功能生效。
if search_enabled():
    logger.info(
        "[配置] 联网搜索=已启用 服务商=%s 结果数=%d 超时=%.0fs 最大轮数=%d",
        SEARCH_PROVIDER, SEARCH_MAX_RESULTS, SEARCH_TIMEOUT, SEARCH_MAX_ROUNDS,
    )
else:
    logger.info("[配置] 联网搜索=未启用（未配置 SEARCH_API_KEY，模型将无法联网检索）")


# ==================== FastAPI 应用 ====================
app = FastAPI(
    title="Eden AI 代理后端",
    description="为 Android 客户端提供大模型对话中转，密钥保存在服务端。",
    version="1.0.0",
)

# 跨域 CORS：方便本地模拟器 / 浏览器 / 前端页面直接联调。
# 注意：allow_credentials=True 与 allow_origins=["*"] 不能共存，故保持 False。
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ==================== 全局异常处理 ====================
# 约定：业务失败一律 HTTP 200 + code!=0（见 README「统一信封」）。
# 若把异常直接抛成 HTTP 500，Android 端 Retrofit 会先抛 HttpException，
# 拿不到 message 文案，只能弹「AI 服务异常（HTTP 500）」。故统一在此兜底。
# 注意：HTTPException（如 401 鉴权失败）仍由 FastAPI 自己的处理器接管，不受影响。
@app.exception_handler(RequestValidationError)
async def on_validation_error(request: Request, exc: RequestValidationError) -> JSONResponse:
    """请求体不符合接口定义时，记录原始 body 并按统一信封返回，便于定位字段名/结构问题。"""
    raw_body = (await request.body()).decode("utf-8", "ignore")
    logger.error(
        "[请求校验失败] %s %s 校验错误=%s 收到请求体=%s",
        request.method,
        request.url.path,
        exc.errors(),
        raw_body[:1000],
    )
    return JSONResponse(
        status_code=200,
        content={"code": 1, "message": "请求参数不合法，请检查 messages 等字段", "data": None},
    )


@app.exception_handler(Exception)
async def on_unhandled_error(request: Request, exc: Exception) -> JSONResponse:
    """兜底：任何未捕获异常都打印完整堆栈，并返回统一信封，避免直接 500 崩溃。"""
    logger.exception("[未处理异常] %s %s", request.method, request.url.path)
    return JSONResponse(
        status_code=200,
        content={"code": 1, "message": "服务内部错误，请稍后重试", "data": None},
    )


# ==================== 请求 / 响应模型 ====================
class Message(BaseModel):
    """一条对话消息。role 只允许 system / user / assistant。"""

    role: Literal["system", "user", "assistant"]
    content: str = Field(..., min_length=1, max_length=MAX_CONTENT_LENGTH)


class ChatRequest(BaseModel):
    """
    聊天请求体。

    messages: 完整对话上下文，第一条通常是人设 system 提示词。
    stream:   true 时返回 SSE 流式文本，false（默认）时一次性返回完整文本。
    """

    messages: List[Message] = Field(..., min_length=1, max_length=MAX_MESSAGES)
    stream: bool = False
    temperature: float = Field(0.8, ge=0.0, le=2.0)
    max_tokens: int = Field(512, ge=1, le=4096)


class ChatData(BaseModel):
    reply: str


class ChatResponse(BaseModel):
    """统一响应信封，与 App 的账号接口保持一致。"""

    code: int = 0
    message: str = "ok"
    data: Optional[ChatData] = None


class HealthResponse(BaseModel):
    status: str
    model: str
    key_configured: bool


# ---------- 账号体系（与 Android 端 AuthModels.kt 字段保持一致） ----------
class SendCodeRequest(BaseModel):
    """发送邮箱验证码。scene 由客户端携带（register/login/reset），当前不参与校验。"""

    email: str = Field(..., max_length=254)
    scene: Optional[str] = Field(None, max_length=32)


class SendCodeData(BaseModel):
    # 字段名保持驼峰，与客户端 SendCodeData.expireIn 对应
    expireIn: int


class SendCodeResponse(BaseModel):
    code: int = 0
    message: str = "ok"
    data: Optional[SendCodeData] = None


class RegisterRequest(BaseModel):
    """注册。按需求只需 email + code；password 为客户端附带的可选字段。"""

    email: str = Field(..., max_length=254)
    code: str = Field(..., min_length=1, max_length=10)
    password: Optional[str] = Field(None, max_length=128)


class LoginRequest(BaseModel):
    """密码登录。"""

    email: str = Field(..., max_length=254)
    password: str = Field(..., min_length=1, max_length=128)


class CodeLoginRequest(BaseModel):
    """验证码登录。"""

    email: str = Field(..., max_length=254)
    code: str = Field(..., min_length=1, max_length=10)


class ResetPasswordRequest(BaseModel):
    """忘记密码：邮箱 + 验证码 + 新密码（无需登录态）。字段名与客户端 ResetPasswordRequest 一致。"""

    email: str = Field(..., max_length=254)
    code: str = Field(..., min_length=1, max_length=10)
    newPassword: str = Field(..., min_length=6, max_length=128)


class ChangePasswordRequest(BaseModel):
    """修改密码（需登录态）。字段名与客户端 ChangePasswordRequest 一致。"""

    oldPassword: str = Field(..., min_length=1, max_length=128)
    newPassword: str = Field(..., min_length=6, max_length=128)


class UserInfo(BaseModel):
    id: int
    email: str
    nickname: Optional[str] = None
    avatar: Optional[str] = None


class AuthData(BaseModel):
    token: str
    user: UserInfo


class AuthResponse(BaseModel):
    """注册 / 登录的统一响应信封（客户端要求成功时必须有 data.token）。"""

    code: int = 0
    message: str = "ok"
    data: Optional[AuthData] = None


class OkResponse(BaseModel):
    """无 data 载荷的成功/失败响应（重置密码、修改密码等）。"""

    code: int = 0
    message: str = "ok"
    data: Optional[dict] = None


class ProfileResponse(BaseModel):
    """获取当前用户信息。data 直接就是用户对象（与客户端 ApiResponse<UserInfo> 对应）。"""

    code: int = 0
    message: str = "ok"
    data: Optional[UserInfo] = None


# ---------- 业务数据同步（与 Android 端 SyncModels.kt 字段保持一致） ----------
class SyncDoc(BaseModel):
    """一条业务数据文档。key 为业务标识（如 chat:<角色ID> / diary），value 是 JSON 字符串。"""

    key: str = Field(..., min_length=1, max_length=200)
    value: str = Field(..., max_length=2_000_000)
    updatedAt: Optional[int] = None


class SyncPushRequest(BaseModel):
    """批量上传业务数据文档。"""

    docs: List[SyncDoc] = Field(..., min_length=1, max_length=200)


class SyncData(BaseModel):
    docs: List[SyncDoc]


class SyncResponse(BaseModel):
    code: int = 0
    message: str = "ok"
    data: Optional[SyncData] = None


# ==================== 工具函数 ====================
def verify_token(authorization: Optional[str]) -> None:
    """
    最简单的请求校验：仅在配置了 APP_TOKEN 时生效。
    不配置就完全放行，方便本地联调。
    """
    if not APP_TOKEN:
        return
    if authorization != f"Bearer {APP_TOKEN}":
        raise HTTPException(status_code=401, detail="无效的访问令牌")


def model_config_error() -> Optional[str]:
    """
    检查大模型配置是否就绪。

    缺失时返回可直接提示给用户的文案，就绪时返回 None。
    刻意不抛 HTTPException(500)：本服务约定业务失败一律 HTTP 200 + code!=0，
    否则 Android 端 Retrofit 先抛 HttpException，用户只能看到「HTTP 500」。
    """
    if not ARK_API_KEY:
        return "服务端未配置大模型密钥（ARK_API_KEY），请联系管理员"
    if not ARK_MODEL_NAME:
        return "服务端未配置大模型名称（ARK_MODEL_NAME），请联系管理员"
    return None


# 下发给模型的工具定义（OpenAI function calling 格式）。
# 模型判断用户问的是现实世界的实时/事实信息时，会主动调用它去联网检索。
WEB_SEARCH_TOOL: dict = {
    "type": "function",
    "function": {
        "name": "web_search",
        "description": (
            "联网搜索现实世界的最新或事实性信息（新闻、品牌、公司、地点、价格、"
            "人物、产品、事件等）。当你不确定答案，或问题涉及近期/客观事实时，"
            "应优先调用本工具获取真实资料，不要凭记忆编造。"
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "用于搜索引擎的检索关键词，尽量简洁、准确。",
                }
            },
            "required": ["query"],
        },
    },
}


def build_upstream_payload(
    messages: List[dict],
    *,
    stream: bool,
    temperature: float,
    max_tokens: int,
    with_tools: bool = True,
) -> dict:
    """
    把对话消息转换成大模型 OpenAI 兼容格式的请求体。

    with_tools=True 且已配置搜索 Key 时，一并向模型下发联网搜索工具；
    最后一轮（with_tools=False）不再下发，强制模型基于已检索到的资料作答。
    """
    payload = {
        "model": ARK_MODEL_NAME,
        "messages": messages,
        "temperature": temperature,
        "max_tokens": max_tokens,
        "stream": stream,
    }
    if with_tools and search_enabled():
        payload["tools"] = [WEB_SEARCH_TOOL]
        payload["tool_choice"] = "auto"
    return payload


async def run_web_search(query: str) -> str:
    """
    执行一次联网搜索，返回可直接交给模型阅读的文本结果。

    设计为「绝不抛异常」：未配置 Key、超时、上游报错等任何情况下都返回可读文本，
    由模型据此决定继续回答还是如实说「不确定」，避免搜索失败拖垮整轮对话。

    当前适配博查 BochaAI 的 Web Search API；更换服务商只需改写本函数内部实现，
    保持「入参 query 字符串、出参可读文本」的契约即可。
    """
    if not search_enabled():
        return "（未启用联网搜索）"

    query = (query or "").strip()
    if not query:
        return "（搜索关键词为空）"

    payload = {
        "query": query,
        "freshness": "noLimit",  # 不限时间范围，兼顾即时与历史事实
        "summary": True,         # 让服务商返回已提炼的摘要，比原始 snippet 更完整
        "count": SEARCH_MAX_RESULTS,
    }
    headers = {
        "Authorization": f"Bearer {SEARCH_API_KEY}",
        "Content-Type": "application/json",
    }
    started = time.time()
    try:
        async with httpx.AsyncClient(timeout=SEARCH_TIMEOUT) as client:
            resp = await client.post(SEARCH_BASE_URL, headers=headers, json=payload)
        logger.info(
            "[联网搜索] 关键词=%s 状态=%d 耗时=%.2fs",
            query, resp.status_code, time.time() - started,
        )
        if resp.status_code != 200:
            logger.warning(
                "[联网搜索] 上游返回 HTTP %s：%s", resp.status_code, resp.text[:300]
            )
            return "（联网搜索暂时不可用）"
        data = resp.json()
    except Exception as exc:  # noqa: BLE001 - 搜索失败不能影响主流程
        logger.warning("[联网搜索] 请求失败：%s", exc)
        return "（联网搜索暂时不可用）"

    # 博查返回结构：data.webPages.value[]
    pages = (((data or {}).get("data") or {}).get("webPages") or {}).get("value") or []
    if not pages:
        return f"（没有检索到与「{query}」相关的结果）"

    lines: List[str] = []
    for i, page in enumerate(pages[:SEARCH_MAX_RESULTS], 1):
        title = str(page.get("name") or "").strip()
        snippet = str(page.get("summary") or page.get("snippet") or "").strip()
        site = str(page.get("siteName") or "").strip()
        url = str(page.get("url") or "").strip()
        lines.append(f"{i}. {title}（{site}）\n   {snippet}\n   {url}")
    return f"关于「{query}」的搜索结果：\n" + "\n".join(lines)


async def run_tool_calls(tool_calls: List[dict]) -> List[dict]:
    """
    执行模型请求的工具调用，返回可追加进 messages 的 role=tool 结果消息。

    当前只支持 web_search；遇到未知工具时返回提示文本，避免模型在同一轮反复请求。
    """
    results: List[dict] = []
    for call in tool_calls:
        call_id = call.get("id") or ""
        func = call.get("function") or {}
        name = func.get("name") or ""
        raw_args = func.get("arguments") or "{}"
        if name == "web_search":
            try:
                args = json.loads(raw_args) if isinstance(raw_args, str) else (raw_args or {})
            except (ValueError, TypeError):
                args = {}
            query = args.get("query") if isinstance(args, dict) else None
            content = await run_web_search(str(query or ""))
        else:
            content = f"（不支持的工具：{name}）"
        results.append({"role": "tool", "tool_call_id": call_id, "content": content})
    return results


def upstream_headers() -> dict:
    """大模型请求头，密钥在此拼接，只发给上游，不回传客户端。"""
    return {
        "Authorization": f"Bearer {ARK_API_KEY}",
        "Content-Type": "application/json",
    }


def extract_last_user_question(req: ChatRequest) -> str:
    """取出最后一条用户提问，仅用于日志打印。"""
    for msg in reversed(req.messages):
        if msg.role == "user":
            # 日志过长会刷屏，截断到 100 字
            return msg.content[:100]
    return "(无用户提问)"


async def post_upstream_with_retry(url: str, payload: dict) -> httpx.Response:
    """
    带指数退避重试的上游 POST 请求。

    仅对「网络超时」与「5xx 服务端错误」重试，最多 UPSTREAM_MAX_RETRIES 次，
    间隔按 base * 2^n 递增（1s / 2s / 4s）。4xx（鉴权失败 / 参数错误）不重试，
    因为重试也不会成功，只会白白增加耗时。
    """
    last_exc: Optional[Exception] = None
    for attempt in range(1, UPSTREAM_MAX_RETRIES + 1):
        started = time.time()
        try:
            async with httpx.AsyncClient(timeout=REQUEST_TIMEOUT) as client:
                resp = await client.post(url, headers=upstream_headers(), json=payload)
            # 打印完整耗时与响应摘要，便于区分「网络超时」还是「模型推理超时」。
            logger.info(
                "[上游请求] 第%d/%d次 状态=%d 耗时=%.2fs",
                attempt, UPSTREAM_MAX_RETRIES, resp.status_code, time.time() - started,
            )
            if resp.status_code >= 500:
                logger.warning(
                    "[上游请求] 第%d次返回 HTTP %s：%s",
                    attempt, resp.status_code, resp.text[:300],
                )
            else:
                return resp
        except httpx.TimeoutException as exc:
            last_exc = exc
            logger.warning(
                "[上游请求] 第%d/%d次超时（%.1fs）", attempt, UPSTREAM_MAX_RETRIES, REQUEST_TIMEOUT,
            )
        except httpx.HTTPError as exc:
            last_exc = exc
            logger.warning("[上游请求] 第%d/%d次连接失败：%s", attempt, UPSTREAM_MAX_RETRIES, exc)

        # 还有下一次机会时，按指数退避等待再重试
        if attempt < UPSTREAM_MAX_RETRIES:
            delay = UPSTREAM_RETRY_BASE_DELAY * (2 ** (attempt - 1))
            logger.info("[上游请求] %.1fs 后进行第%d次重试", delay, attempt + 1)
            await asyncio.sleep(delay)

    if last_exc is not None:
        raise last_exc
    return resp


# ---------- 账号体系工具函数 ----------
def normalize_email(email: str) -> str:
    """统一小写去空格，避免同一个人用大小写注册出两个账号。"""
    return email.strip().lower()


def is_valid_email(email: str) -> bool:
    return re.match(EMAIL_PATTERN, email) is not None


def hash_password(password: str, salt_hex: Optional[str] = None) -> tuple:
    """返回 (salt_hex, hash_hex)。PBKDF2-HMAC-SHA256，标准库实现，不引入额外依赖。"""
    salt = bytes.fromhex(salt_hex) if salt_hex else secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac(
        "sha256", password.encode("utf-8"), salt, PASSWORD_HASH_ITERATIONS
    )
    return salt.hex(), digest.hex()


def verify_password(password: str, salt_hex: str, expected_hash: str) -> bool:
    _, actual_hash = hash_password(password, salt_hex)
    return secrets.compare_digest(actual_hash, expected_hash)


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


def _row_to_user(row: Optional[sqlite3.Row]) -> Optional[dict]:
    """把 users 表的一行转换成与旧内存结构一致的 dict。"""
    if row is None:
        return None
    return {
        "user_id": row["id"],
        "email": row["email"],
        "nickname": row["nickname"],
        "salt": row["salt"],
        "password_hash": row["password_hash"],
    }


def db_get_user_by_email(email: str) -> Optional[dict]:
    with _store_lock:
        row = _db_conn.execute(
            "SELECT * FROM users WHERE email = ?", (email,)
        ).fetchone()
    return _row_to_user(row)


def db_get_user_by_id(user_id: int) -> Optional[dict]:
    with _store_lock:
        row = _db_conn.execute(
            "SELECT * FROM users WHERE id = ?", (user_id,)
        ).fetchone()
    return _row_to_user(row)


def upsert_user(email: str, password: Optional[str]) -> dict:
    """创建（或更新）账号并持久化到数据库，返回用户记录。

    已存在时：若传入 password 则更新密码，否则保持原样（验证码登录场景）。
    """
    existing = db_get_user_by_email(email)
    if existing is None:
        salt, password_hash = hash_password(password) if password else (None, None)
        with _store_lock:
            cursor = _db_conn.execute(
                "INSERT INTO users (email, nickname, salt, password_hash, created_at)"
                " VALUES (?, ?, ?, ?, ?)",
                (email, email.split("@")[0], salt, password_hash, int(time.time())),
            )
            _db_conn.commit()
            user_id = cursor.lastrowid
        return {"user_id": user_id, "email": email, "nickname": email.split("@")[0]}

    if password:
        set_user_password(email, password)
    return existing


def set_user_password(email: str, password: str) -> None:
    """更新指定账号的密码哈希（注册补设密码 / 重置 / 修改密码共用）。"""
    salt, password_hash = hash_password(password)
    with _store_lock:
        _db_conn.execute(
            "UPDATE users SET salt = ?, password_hash = ? WHERE email = ?",
            (salt, password_hash, email),
        )
        _db_conn.commit()


# ---------- 业务数据（按用户隔离，key -> JSON 文档） ----------
def db_get_all_user_data(user_id: int) -> List[dict]:
    with _store_lock:
        rows = _db_conn.execute(
            "SELECT data_key, value, updated_at FROM user_data WHERE user_id = ?",
            (user_id,),
        ).fetchall()
    return [
        {"key": r["data_key"], "value": r["value"], "updatedAt": r["updated_at"]}
        for r in rows
    ]


def db_upsert_user_data(user_id: int, key: str, value: str, updated_at: int) -> None:
    """写入一条业务数据；仅在传入的 updated_at 不早于已存时间时才覆盖。

    这样即使客户端因网络乱序把旧版本后发到服务端，也不会把更新的数据冲掉
    （时间戳由客户端提供，比较的是同一来源的时间，不受服务端时钟影响）。
    """
    with _store_lock:
        _db_conn.execute(
            "INSERT INTO user_data (user_id, data_key, value, updated_at)"
            " VALUES (?, ?, ?, ?)"
            " ON CONFLICT(user_id, data_key) DO UPDATE SET"
            " value = excluded.value, updated_at = excluded.updated_at"
            " WHERE excluded.updated_at >= user_data.updated_at",
            (user_id, key, value, updated_at),
        )
        _db_conn.commit()


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


# ==================== 接口 ====================
@app.get("/health", summary="存活探针（云托管健康检查）")
async def health_probe() -> dict:
    """
    云托管存活 / 就绪探针专用接口。

    固定返回 `{"status": "ok"}`，不做任何外部依赖调用（不碰数据库、不碰大模型），
    保证探针永远快速成功，避免容器因探针超时被判定为不健康而遭回收。
    """
    return {"status": "ok"}


@app.get("/api/health", response_model=HealthResponse, summary="健康检查")
async def health() -> HealthResponse:
    """探活接口，同时可以确认模型配置是否就绪。"""
    return HealthResponse(
        status="ok",
        model=ARK_MODEL_NAME or "(未配置)",
        key_configured=bool(ARK_API_KEY),
    )


@app.post("/api/chat", response_model=ChatResponse, summary="聊天代理（非流式）")
async def chat(req: ChatRequest, authorization: Optional[str] = Header(default=None)):
    """
    转发聊天请求到大模型并返回文本结果。

    - 默认非流式（stream=false）：一次性返回完整回复，方便安卓端解析与调试。
    - stream=true 时改为返回 `text/event-stream` 流式文本（见下方 StreamingResponse）。
    """
    verify_token(authorization)

    question = extract_last_user_question(req)
    started = time.time()
    # 打印实际收到的请求体：便于核对客户端字段名/结构是否符合接口定义（过长时截断）。
    logger.info(
        "[聊天请求] 时间=%s 流式=%s 消息数=%d 用户提问=%s 请求体=%s",
        time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(started)),
        req.stream,
        len(req.messages),
        question,
        req.model_dump_json()[:1000],
    )

    # 服务端配置缺失：记录日志并回友好提示（HTTP 200 + code!=0），不抛 500。
    config_error = model_config_error()
    if config_error:
        logger.error("[聊天请求] 服务端配置缺失：%s", config_error)
        return ChatResponse(code=1, message=config_error)

    url = f"{ARK_BASE_URL}chat/completions"
    # 转成上游需要的消息结构（后续工具循环会在此基础上追加 assistant / tool 消息）。
    messages = [m.model_dump() for m in req.messages]

    # ---------- 流式：SSE 逐段返回 ----------
    if req.stream:
        payload = build_upstream_payload(
            messages,
            stream=True,
            temperature=req.temperature,
            max_tokens=req.max_tokens,
        )
        return StreamingResponse(
            _stream_upstream(url, payload, started),
            media_type="text/event-stream",
        )

    # ---------- 非流式：一次性返回（含联网搜索工具循环） ----------
    return await _non_stream_completion(req, messages, url, started, question)


async def _non_stream_completion(
    req: ChatRequest, messages: List[dict], url: str, started: float, question: str
) -> ChatResponse:
    """
    非流式补全，带工具调用（联网搜索）循环。

    每轮把当前 messages 发给上游：
      - 模型返回 tool_calls → 执行搜索、把结果作为 role=tool 消息追加，进入下一轮；
      - 否则取 content 作为最终回复返回。
    最多 SEARCH_MAX_ROUNDS 轮，最后一轮不再下发工具，强制模型基于已有资料作答。
    未配置搜索 Key 时只跑一轮，行为与接入前一致。
    """
    for round_index in range(SEARCH_MAX_ROUNDS + 1):
        payload = build_upstream_payload(
            messages,
            stream=False,
            temperature=req.temperature,
            max_tokens=req.max_tokens,
            with_tools=round_index < SEARCH_MAX_ROUNDS,
        )

        try:
            resp = await post_upstream_with_retry(url, payload)
        except httpx.TimeoutException:
            logger.warning("[聊天请求] 上游超时（%.1fs）用户提问=%s", time.time() - started, question)
            # 返回 200 + code!=0，让 Android 端能把 message 直接提示给用户
            return ChatResponse(code=1, message="大模型响应超时，请点击重试")
        except httpx.HTTPError as exc:
            logger.error("[聊天请求] 上游连接失败：%s", exc)
            return ChatResponse(code=1, message=f"连接大模型失败：{exc}")

        if resp.status_code != 200:
            # 上游错误（鉴权失败 / 额度不足 / 限流……）原样记录，但只把简要信息给客户端
            logger.error(
                "[聊天请求] 上游返回 HTTP %s：%s", resp.status_code, resp.text[:500]
            )
            return ChatResponse(
                code=1, message=f"大模型服务异常（HTTP {resp.status_code}）"
            )

        try:
            data = resp.json()
        except ValueError:
            # 上游 200 但返回的不是 JSON（如网关 HTML 报错页），同样按友好文案返回
            logger.error("[聊天请求] 上游返回非 JSON：%s", resp.text[:500])
            return ChatResponse(code=1, message="大模型返回格式异常")

        try:
            message = data["choices"][0]["message"]
        except (KeyError, IndexError, TypeError):
            logger.error("[聊天请求] 上游返回结构异常：%s", str(data)[:500])
            return ChatResponse(code=1, message="大模型返回格式异常")

        tool_calls = message.get("tool_calls") if isinstance(message, dict) else None
        if tool_calls and round_index < SEARCH_MAX_ROUNDS:
            logger.info(
                "[聊天请求] 第%d轮触发工具调用：%s",
                round_index + 1,
                [(c.get("function") or {}).get("name") for c in tool_calls],
            )
            # 把模型的 tool_calls 消息原样回填，再追加工具执行结果，进入下一轮
            messages.append(
                {
                    "role": "assistant",
                    "content": (message.get("content") or ""),
                    "tool_calls": tool_calls,
                }
            )
            messages.extend(await run_tool_calls(tool_calls))
            continue

        reply = message.get("content") if isinstance(message, dict) else None
        if not isinstance(reply, str) or not reply:
            logger.error("[聊天请求] 上游返回结构异常：%s", str(data)[:500])
            return ChatResponse(code=1, message="大模型返回格式异常")

        logger.info(
            "[聊天请求] 完成 耗时=%.2fs 用户提问=%s 回复=%s",
            time.time() - started,
            question,
            reply[:60].replace("\n", " "),
        )
        return ChatResponse(code=0, message="ok", data=ChatData(reply=reply))

    # 循环内每轮都会 return 或 continue，正常不会走到这里；兜底避免漏返回。
    logger.error("[聊天请求] 工具循环结束仍未得到回复：%s", question)
    return ChatResponse(code=1, message="大模型返回格式异常")


async def _stream_upstream(url: str, payload: dict, started: float) -> AsyncIterator[str]:
    """
    流式转发：把上游 SSE 的增量文本按 `data: <JSON 字符串>\\n\\n` 输出。

    支持联网搜索工具循环：若模型某一轮只返回 tool_calls（无正文），则执行搜索、
    把结果追加进 messages 后再开一轮流式请求；纯文本轮则实时推送，行为与接入前一致。

    增量用 ``json.dumps`` 转义后再写入 ``data:``，否则模型输出里的换行会被
    SSE 帧的行分隔符切断，换行后的内容会丢。客户端按 ``[DONE]`` / ``[ERROR]``
    前缀识别结束与错误，其余 payload 用 JSON 字符串解码。

    错误帧格式为 ``data: [ERROR:<code>] <文案>``：``code`` 为上游 HTTP 状态码
    （连接失败 / 超时记为 0），客户端据此判断是否值得重试（4xx 不重试）。

    重试策略（首帧前重试）：本轮只要还未向客户端吐出任何增量，上游 5xx / 连接
    失败 / 超时即按指数退避重试；一旦吐过字再失败只能报错，否则重试会重复输出。
    """
    messages: List[dict] = payload.get("messages") or []
    base_payload = {k: v for k, v in payload.items() if k != "messages"}

    for round_index in range(SEARCH_MAX_ROUNDS + 1):
        round_payload = dict(base_payload)
        round_payload["messages"] = messages
        # 最后一轮不再下发工具，强制模型基于已检索到的资料作答。
        if round_index >= SEARCH_MAX_ROUNDS:
            round_payload.pop("tools", None)
            round_payload.pop("tool_choice", None)

        # 按 index 聚合流式分片返回的 tool_calls（id / name 可能分多个 chunk 到达）
        tool_calls: Dict[int, dict] = {}

        # 首帧前重试：本轮已吐过增量（round_yielded=True）则失败后不再重试，避免重复输出。
        attempt = 0
        round_yielded = False
        while True:
            attempt += 1
            tool_calls = {}
            try:
                async with httpx.AsyncClient(timeout=REQUEST_TIMEOUT) as client:
                    async with client.stream(
                        "POST", url, headers=upstream_headers(), json=round_payload
                    ) as resp:
                        if resp.status_code != 200:
                            body = await resp.aread()
                            logger.error(
                                "[流式] 第%d/%d次上游返回 HTTP %s：%s",
                                attempt, UPSTREAM_MAX_RETRIES, resp.status_code, body[:300],
                            )
                            retryable = resp.status_code == 429 or resp.status_code >= 500
                            if retryable and not round_yielded and attempt < UPSTREAM_MAX_RETRIES:
                                await asyncio.sleep(
                                    UPSTREAM_RETRY_BASE_DELAY * (2 ** (attempt - 1))
                                )
                                continue
                            yield (
                                f"data: [ERROR:{resp.status_code}] "
                                f"大模型服务异常（HTTP {resp.status_code}）\n\n"
                            )
                            return

                        async for line in resp.aiter_lines():
                            if not line.startswith("data:"):
                                continue
                            chunk = line[len("data:"):].strip()
                            if chunk == "[DONE]":
                                break
                            try:
                                delta = json.loads(chunk)["choices"][0].get("delta") or {}
                            except (KeyError, IndexError, ValueError, TypeError):
                                continue

                            for tc in delta.get("tool_calls") or []:
                                idx = tc.get("index", 0)
                                slot = tool_calls.setdefault(
                                    idx,
                                    {"id": "", "type": "function",
                                     "function": {"name": "", "arguments": ""}},
                                )
                                if tc.get("id"):
                                    slot["id"] = tc["id"]
                                fn = tc.get("function") or {}
                                if fn.get("name"):
                                    slot["function"]["name"] = fn["name"]
                                if fn.get("arguments"):
                                    slot["function"]["arguments"] += fn["arguments"]

                            content = delta.get("content")
                            if content:
                                if not round_yielded:
                                    logger.info("[流式] 首帧 %.2fs", time.time() - started)
                                round_yielded = True
                                yield f"data: {json.dumps(content, ensure_ascii=False)}\n\n"
            except httpx.HTTPError as exc:
                logger.error(
                    "[流式] 第%d/%d次上游连接失败：%s", attempt, UPSTREAM_MAX_RETRIES, exc
                )
                if not round_yielded and attempt < UPSTREAM_MAX_RETRIES:
                    await asyncio.sleep(UPSTREAM_RETRY_BASE_DELAY * (2 ** (attempt - 1)))
                    continue
                yield "data: [ERROR:0] 连接大模型失败\n\n"
                return
            break

        # 本轮只请求了工具（无正文）：执行搜索后进入下一轮。
        if tool_calls and round_index < SEARCH_MAX_ROUNDS:
            calls = [tool_calls[i] for i in sorted(tool_calls)]
            logger.info(
                "[流式] 第%d轮触发工具调用：%s",
                round_index + 1,
                [(c.get("function") or {}).get("name") for c in calls],
            )
            messages.append({"role": "assistant", "content": "", "tool_calls": calls})
            messages.extend(await run_tool_calls(calls))
            continue

        break

    logger.info("[流式] 完成 耗时=%.2fs", time.time() - started)
    yield "data: [DONE]\n\n"


# ==================== 账号体系接口 ====================
@app.post(
    "/api/v1/auth/email/code",
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


@app.post("/api/v1/auth/register", response_model=AuthResponse, summary="注册（邮箱 + 验证码）")
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


@app.post("/api/v1/auth/login", response_model=AuthResponse, summary="密码登录")
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


@app.post("/api/v1/auth/login/code", response_model=AuthResponse, summary="验证码登录")
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


@app.post(
    "/api/v1/auth/password/reset", response_model=OkResponse, summary="忘记密码（重置密码）"
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


@app.post(
    "/api/v1/auth/password/change", response_model=OkResponse, summary="修改密码（需登录态）"
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


@app.get("/api/v1/user/profile", response_model=ProfileResponse, summary="获取当前用户信息")
async def get_profile(authorization: Optional[str] = Header(default=None)) -> ProfileResponse:
    """按登录凭证返回当前用户信息，凭证无效时返回 401（客户端据此自动登出）。"""
    user = require_session(authorization)

    return ProfileResponse(
        code=0,
        message="ok",
        data=UserInfo(
            id=user["user_id"],
            email=user["email"],
            nickname=user.get("nickname"),
        ),
    )


@app.get(
    "/api/v1/sync/data",
    response_model=SyncResponse,
    summary="拉取当前用户全部业务数据（需登录态）",
)
async def pull_sync_data(authorization: Optional[str] = Header(default=None)) -> SyncResponse:
    """
    登录成功后客户端调用：返回该用户在后端保存的全部业务数据文档。

    要求携带 `Authorization: Bearer <JWT>`；凭证无效返回 401，客户端据此跳回登录页。
    """
    user = require_session(authorization)
    docs = db_get_all_user_data(user["user_id"])
    logger.info("[同步] 拉取 email=%s 文档数=%d", user["email"], len(docs))
    return SyncResponse(code=0, message="ok", data=SyncData(docs=docs))


@app.post(
    "/api/v1/sync/data",
    response_model=OkResponse,
    summary="上传 / 更新业务数据（需登录态）",
)
async def push_sync_data(
    req: SyncPushRequest, authorization: Optional[str] = Header(default=None)
) -> OkResponse:
    """客户端本地业务数据变更后上传，按 (user_id, key) 写入，且仅更新较新的版本（LWW）。"""
    user = require_session(authorization)
    now = int(time.time())
    for doc in req.docs:
        # 客户端带更新时间戳时以其为准（两端时间同源、可比较），缺失才退回服务端当前时间。
        db_upsert_user_data(user["user_id"], doc.key, doc.value, doc.updatedAt or now)
    logger.info(
        "[同步] 上传 email=%s 文档数=%d keys=%s",
        user["email"],
        len(req.docs),
        [d.key for d in req.docs][:10],
    )
    return OkResponse(code=0, message="ok")


if __name__ == "__main__":
    # 直接 `python app/main.py` 也能启动，等价于 uvicorn 命令。
    # 注意：线上容器绝不加 reload，避免文件监听触发反复重启。
    import uvicorn

    uvicorn.run("app.main:app", host="0.0.0.0", port=DEFAULT_PORT)