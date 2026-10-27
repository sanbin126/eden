package com.example.eden.core.network

/**
 * 大模型调用失败的统一异常，额外携带「是否值得重试」。
 *
 * 之所以不直接用 [java.io.IOException]，是因为流式接口失败时后端仍返回 HTTP 200，
 * 错误信息藏在 SSE 帧 `data: [ERROR:<code>] <文案>` 里，由业务层解析。
 * 解析后可据此判断：4xx 属确定性错误不重试，5xx / 超时 / 限流才重试。
 */
class LlmException(
    message: String,
    val retryable: Boolean = true
) : Exception(message)

/**
 * 判断上游 HTTP 状态码是否值得重试：
 * - 0：连接失败 / 超时（后端无法拿到状态码时的约定值）
 * - 408：请求超时
 * - 429：限流
 * - 5xx：服务端错误
 *
 * 其余 4xx 为确定性错误（参数错、鉴权失败等），重试无意义。
 */
fun isRetryableErrorCode(code: Int): Boolean =
    code == 0 || code == 408 || code == 429 || code in 500..599

/**
 * 解析后端 SSE 错误帧 `[ERROR:<code>] <文案>`（见 backend/app/main.py `_stream_upstream`）。
 *
 * 兼容旧格式 `[ERROR] <文案>`（无状态码），此时按可重试处理。
 * 无法解析出文案时给出兜底提示，保证用户始终能看到可点击重试的入口。
 */
fun parseSseError(payload: String): LlmException {
    val body = payload.removePrefix("[ERROR")
    val code = body.substringAfter(':', "").substringBefore(']').trim().toIntOrNull()
    val message = body.substringAfter(']', "").trim().ifEmpty { "AI 回复失败" }
    return LlmException(message, retryable = code == null || isRetryableErrorCode(code))
}
