"""健康检查 / 存活探针路由。"""

from fastapi import APIRouter

from ..config import ARK_API_KEY, ARK_MODEL_NAME
from ..models import HealthResponse

router = APIRouter()


# ==================== 接口 ====================
@router.get("/health", summary="存活探针（云托管健康检查）")
async def health_probe() -> dict:
    """
    云托管存活 / 就绪探针专用接口。

    固定返回 `{"status": "ok"}`，不做任何外部依赖调用（不碰数据库、不碰大模型），
    保证探针永远快速成功，避免容器因探针超时被判定为不健康而遭回收。
    """
    return {"status": "ok"}


@router.get("/api/health", response_model=HealthResponse, summary="健康检查")
async def health() -> HealthResponse:
    """探活接口，同时可以确认模型配置是否就绪。"""
    return HealthResponse(
        status="ok",
        model=ARK_MODEL_NAME or "(未配置)",
        key_configured=bool(ARK_API_KEY),
    )
