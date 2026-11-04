"""上游大模型调用：请求体构造、联网搜索工具、重试与错误文案。

本模块只负责与上游交互的纯逻辑；HTTP 路由见 routers/chat.py。
重试次数、退避间隔、超时与文案均与拆分前完全一致。
"""

import asyncio
import json
import time
from typing import List, Optional

import httpx

from .config import (
    ARK_API_KEY,
    ARK_MODEL_NAME,
    REQUEST_TIMEOUT,
    SEARCH_API_KEY,
    SEARCH_BASE_URL,
    SEARCH_MAX_RESULTS,
    SEARCH_TIMEOUT,
    UPSTREAM_MAX_RETRIES,
    UPSTREAM_RETRY_BASE_DELAY,
    logger,
    search_enabled,
)
from .models import ChatRequest


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
