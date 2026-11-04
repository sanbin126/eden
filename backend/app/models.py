"""请求 / 响应模型（字段与 Android 端保持一致，签名为对外契约，不得改动）。"""

from typing import List, Literal, Optional

from pydantic import BaseModel, Field

from .config import MAX_CONTENT_LENGTH, MAX_MESSAGES


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
