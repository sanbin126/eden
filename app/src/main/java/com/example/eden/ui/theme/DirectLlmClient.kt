package com.example.eden.ui.theme

import com.example.eden.BuildConfig
import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Streaming
import java.util.concurrent.TimeUnit

// =====================================================================================
// ⚠️⚠️ 仅供本地开发自用，禁止打包上线 ⚠️⚠️
// 直连模式会把大模型 API Key 打进客户端包内，一旦发布极易被逆向窃取。
// 请勿把 LLM_DIRECT_API_KEY 写入正式包；release 构建已由 LlmModeManager 强制回退 backend。
// =====================================================================================

private val SSE_MEDIA: MediaType = "text/event-stream".toMediaType()

private val directGson = Gson()

// ---------------- OpenAI 兼容（DeepSeek / 火山方舟）请求响应模型 ----------------

private data class OpenAiMessage(val role: String, val content: String)

private data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val stream: Boolean = false,
    val temperature: Double = 0.8,
    val max_tokens: Int = 512
)

private data class OpenAiChoice(
    val message: OpenAiMessage? = null,
    val delta: OpenAiMessage? = null
)

private data class OpenAiChatResponse(val choices: List<OpenAiChoice>? = null)

private interface DeepSeekApi {

    /** 非流式：一次性拿完整回复（用于记忆压缩等）。 */
    @POST("chat/completions")
    suspend fun chat(@Body request: OpenAiChatRequest): OpenAiChatResponse

    /** 流式：返回上游 SSE 原始响应体，由 [NormalizeBody] 逐行归一化。 */
    @Streaming
    @POST("chat/completions")
    suspend fun chatStream(@Body request: OpenAiChatRequest): ResponseBody
}

private object DirectClient {

    // 直连客户端：超时整体放宽（用户规则 1），读超时 90s 覆盖模型首字 / 长推理延迟。
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .addInterceptor(DirectAuthInterceptor())
        // 指数退避重试（用户规则 2）：500/超时自动重试，最多 3 次，间隔 1s / 2s / 4s。
        .addInterceptor(RetryInterceptor(maxRetries = 3, baseDelayMs = 1000L))
        // 仅在 debug 构建打印请求耗时（用户规则 6），release 不打印避免泄露密钥。
        .apply { if (BuildConfig.DEBUG) addInterceptor(LlmLoggingInterceptor("LLM-DIRECT")) }
        .build()

    val api: DeepSeekApi = Retrofit.Builder()
        .baseUrl(BuildConfig.LLM_DIRECT_BASE_URL)   // 必须以 "/" 结尾
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(DeepSeekApi::class.java)
}

/** 直连网关：注入与后端同款契约，非流式返回 [ChatResponse]，流式返回归一化后的 SSE。 */
internal object DirectGateway {

    suspend fun chat(request: ChatRequest): ChatResponse {
        if (BuildConfig.LLM_DIRECT_API_KEY.isBlank()) {
            return ChatResponse(code = 1, message = "直连模式未配置 API Key（LLM_DIRECT_API_KEY）")
        }
        return try {
            val resp = DirectClient.api.chat(buildRequest(request, stream = false))
            val content = resp.choices?.firstOrNull()?.message?.content
            if (content.isNullOrBlank()) {
                ChatResponse(code = 1, message = "模型返回为空")
            } else {
                ChatResponse(code = 0, message = "ok", data = ChatData(content))
            }
        } catch (e: Exception) {
            ChatResponse(code = 1, message = "直连请求失败：${e.message ?: "未知错误"}")
        }
    }

    suspend fun chatStream(request: ChatRequest): ResponseBody {
        if (BuildConfig.LLM_DIRECT_API_KEY.isBlank()) {
            return "data: [ERROR] 直连模式未配置 API Key（LLM_DIRECT_API_KEY）\n\n"
                .toResponseBody(SSE_MEDIA)
        }
        return try {
            val upstream = DirectClient.api.chatStream(buildRequest(request, stream = true))
            NormalizeBody(upstream, directGson)
        } catch (e: Exception) {
            "data: [ERROR] 直连请求失败：${e.message ?: "未知错误"}\n\n"
                .toResponseBody(SSE_MEDIA)
        }
    }

    /** 把后端 [ChatRequest] 原样映射成 OpenAI 兼容请求，复用现有 payload，业务零改动。 */
    private fun buildRequest(request: ChatRequest, stream: Boolean) = OpenAiChatRequest(
        model = BuildConfig.LLM_DIRECT_MODEL,
        messages = request.messages.map { OpenAiMessage(it.role, it.content) },
        stream = stream,
        temperature = request.temperature ?: 0.8
    )
}

// ---------------- 流式归一化：把 OpenAI SSE → 后端同款 SSE ----------------
//
// 上游（DeepSeek）：
//   data: {"choices":[{"delta":{"content":"你"}}]}
//   data: [DONE]
// 归一化后（后端契约）：
//   data: "你"
//   data: [DONE]
//
// 这样 ChatRepository.requestReplyStream 的旧解析逻辑可以完全复用、一行不动。

private class NormalizeBody(
    private val upstream: ResponseBody,
    private val gson: Gson
) : ResponseBody() {

    override fun contentType(): MediaType = SSE_MEDIA
    override fun contentLength(): Long = -1L
    override fun source(): BufferedSource = NormalizeSource(upstream.source(), gson).buffer()
}

private class NormalizeSource(
    private val upstream: BufferedSource,
    private val gson: Gson
) : Source {

    private val out = Buffer()
    private var finished = false
    private var doneSent = false
    private var sawData = false
    private val rawFallback = Buffer()

    override fun read(sink: Buffer, byteCount: Long): Long {
        while (out.size == 0L && !finished) {
            pump()
        }
        if (out.size == 0L) return -1L
        return out.read(sink, byteCount)
    }

    override fun timeout(): Timeout = upstream.timeout()

    override fun close() {
        upstream.close()
    }

    private fun pump() {
        val line = upstream.readUtf8Line()
        if (line == null) {
            finished = true
            // 整个响应体都不是 SSE（例如上游返回了错误 JSON），把原文当错误上报
            if (!sawData && rawFallback.size > 0L) {
                val raw = rawFallback.readUtf8().trim().replace('\n', ' ')
                if (raw.isNotEmpty()) {
                    out.writeUtf8("data: [ERROR] ")
                    out.writeUtf8(raw.take(500))
                    out.writeUtf8("\n\n")
                }
            }
            // 兜底补 DONE，保证前端 readLoop 正常退出
            if (!doneSent) {
                out.writeUtf8("data: [DONE]\n\n")
                doneSent = true
            }
            return
        }

        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        if (trimmed.startsWith(":")) return          // SSE 注释 / 心跳行，跳过

        if (!trimmed.startsWith("data:")) {          // 非 data 行，先缓存，可能是错误体
            rawFallback.writeUtf8(line).writeUtf8("\n")
            return
        }

        val payload = trimmed.removePrefix("data:").trim()
        if (payload.isEmpty()) return

        if (payload == "[DONE]") {
            out.writeUtf8("data: [DONE]\n\n")
            doneSent = true
            finished = true
            return
        }

        val content = extractDelta(payload)
        if (!content.isNullOrEmpty()) {
            sawData = true
            out.writeUtf8("data: ")
            out.writeUtf8(gson.toJson(content))       // JSON 字符串编码，含换行也安全
            out.writeUtf8("\n\n")
        }
    }

    /** 从 OpenAI 增量分片中取出 content。 */
    private fun extractDelta(payload: String): String? {
        return try {
            val obj = JsonParser.parseString(payload).asJsonObject
            val choices = obj.getAsJsonArray("choices") ?: return null
            if (choices.size() == 0) return null
            val first = choices[0].asJsonObject
            val deltaContent = first.getAsJsonObject("delta")
                ?.get("content")
                ?.let { if (it.isJsonNull) null else it.asString }
            if (!deltaContent.isNullOrEmpty()) return deltaContent
            first.getAsJsonObject("message")
                ?.get("content")
                ?.let { if (it.isJsonNull) null else it.asString }
        } catch (e: Exception) {
            null
        }
    }
}

// ---------------- 拦截器 ----------------

private class DirectAuthInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer ${BuildConfig.LLM_DIRECT_API_KEY}")
            .build()
        return chain.proceed(request)
    }
}
