"""跨模块共享的请求级依赖。"""

from typing import Optional

from fastapi import HTTPException

from .config import APP_TOKEN


def verify_token(authorization: Optional[str]) -> None:
    """
    最简单的请求校验：仅在配置了 APP_TOKEN 时生效。
    不配置就完全放行，方便本地联调。
    """
    if not APP_TOKEN:
        return
    if authorization != f"Bearer {APP_TOKEN}":
        raise HTTPException(status_code=401, detail="无效的访问令牌")
