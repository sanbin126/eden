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

模块划分
--------
config.py   环境变量 / 日志 / 常量
models.py   请求与响应模型
store.py    SQLite 持久化、内存验证码存储与密码哈希
security.py 邮箱 / 验证码 / JWT / 登录态
upstream.py 上游大模型调用与联网搜索工具
deps.py     共享请求依赖
routers/    按业务域拆分的路由（health / chat / auth / user）
"""

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from .config import DB_PATH, DEFAULT_PORT, JWT_EXPIRE_DAYS, logger
from .routers import auth, chat, health, user
from .store import init_db

# 建表在应用创建前完成，保证进程启动后第一次请求即可读写数据库。
init_db()
logger.info("[配置] 数据库=%s JWT 有效期=%d 天", DB_PATH, JWT_EXPIRE_DAYS)

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


# ==================== 路由注册 ====================
# 各路由模块自带完整路径（或 APIRouter prefix），此处不再叠加任何前缀。
app.include_router(health.router)
app.include_router(chat.router)
app.include_router(auth.router)
app.include_router(user.router)


if __name__ == "__main__":
    # 在 backend 目录下 `python -m app.main` 也能启动，等价于 uvicorn 命令。
    # 注意：线上容器绝不加 reload，避免文件监听触发反复重启。
    import uvicorn

    uvicorn.run("app.main:app", host="0.0.0.0", port=DEFAULT_PORT)
