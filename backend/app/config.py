"""
Eden 后端运行时配置。

所有配置都在导入本模块时读取一次（环境变量 / .env），
其余模块统一从这里导入，避免各自重复读取导致取值不一致。
"""

import logging
import os
from pathlib import Path

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
