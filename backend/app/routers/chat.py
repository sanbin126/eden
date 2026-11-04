"""聊天代理路由：/api/chat（非流式 + SSE 流式，含联网搜索工具循环）。"""

import asyncio
import json
import time
from typing import AsyncIterator, Dict, List, Optional

import httpx
from fastapi import APIRouter, Header
from fastapi.responses import StreamingResponse

from ..config import (
    ARK_BASE_URL,
    REQUEST_TIMEOUT,
    SEARCH_MAX_ROUNDS,
    UPSTREAM_MAX_RETRIES,
    UPSTREAM_RETRY_BASE_DELAY,
    logger,
)
from ..deps import verify_token
from ..models import ChatData, ChatRequest, ChatResponse
from ..upstream import (
    build_upstream_payload,
    extract_last_user_question,
    model_config_error,
    post_upstream_with_retry,
    run_tool_calls,
    upstream_headers,
)

router = APIRouter()


@router.post("/api/chat", response_model=ChatResponse, summary="聊天代理（非流式）")
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
