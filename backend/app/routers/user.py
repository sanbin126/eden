"""用户信息与业务数据同步路由：/api/v1/user/profile、/api/v1/sync/data。"""

import time
from typing import Optional

from fastapi import APIRouter, Header

from ..config import logger
from ..models import OkResponse, ProfileResponse, SyncData, SyncPushRequest, SyncResponse, UserInfo
from ..security import require_session
from ..store import db_get_all_user_data, db_upsert_user_data

router = APIRouter()


@router.get("/api/v1/user/profile", response_model=ProfileResponse, summary="获取当前用户信息")
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


@router.get(
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


@router.post(
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
