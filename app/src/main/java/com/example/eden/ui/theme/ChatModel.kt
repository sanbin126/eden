package com.example.eden.ui.theme

/**
 * AI 聊天代理后端的请求/响应模型（对应 server/ 里的 `POST /api/chat`）。
 *
 * 请求：
 * {
 *   "messages": [
 *     {"role": "system",    "content": "人设提示词"},
 *     {"role": "user",      "content": "你好"},
 *     {"role": "assistant", "content": "……嗯，你好。"}
 *   ],
 *   "stream": false
 * }
 *
 * 响应（与账号接口保持同一套信封）：
 * {
 *   "code": 0,
 *   "message": "ok",
 *   "data": {"reply": "……嗯，你好。"}
 * }
 *
 * `stream = true` 时响应改为 `text/event-stream`，逐段返回 JSON 字符串增量，
 * 由 [com.example.eden.data.ChatRepository.requestReplyStream] 消费；记忆压缩
 * 仍走非流式（默认 false），一次性拿完整 JSON。
 */
data class ChatRequest(
    val messages: List<Message>,
    val stream: Boolean = false,
    val temperature: Double? = null,   // 新增：为空则由各网关使用默认值
)

data class Message(val role: String, val content: String)

data class ChatResponse(
    val code: Int = 0,
    val message: String? = null,
    val data: ChatData? = null
)

data class ChatData(val reply: String)
