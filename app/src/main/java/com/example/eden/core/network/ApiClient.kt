package com.example.eden.core.network


import com.example.eden.BuildConfig
import com.example.eden.core.session.SessionManager
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Streaming
import java.util.concurrent.TimeUnit

/**
 * AI 聊天代理接口。
 *
 * 客户端不再直连大模型，也不再持有 API Key：
 * 密钥保存在后端环境变量中，由 server/ 里的 FastAPI 服务转发到火山方舟。
 */
interface EdenChatApi {
    /** 非流式：一次性返回完整回复（用于记忆压缩） */
    @POST("api/chat")
    suspend fun chat(@Body request: ChatRequest): ChatResponse

    /**
     * 流式：返回 SSE 原始响应体，由调用方逐行读取 `data:` 事件。
     * `@Streaming` 让 Retrofit 不把整个 body 缓冲进内存，边读边吐。
     */
    @Streaming
    @POST("api/chat")
    suspend fun chatStream(@Body request: ChatRequest): ResponseBody
}

object ApiClient {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // 大模型首字延迟可能较高，读超时放宽到 60 秒
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // 长连接心跳：避免流式响应在长时间无数据时被中间层（NAT/代理）判定为空闲而断流
        .pingInterval(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("Content-Type", "application/json")
                .apply {
                    // 后端保留简单的请求校验能力：客户端带登录态时一并透传
                    val token = SessionManager.bearerToken()
                    if (token.isNotEmpty()) addHeader("Authorization", token)
                }
                .build()
            chain.proceed(request)
        }
        // 指数退避重试：兜住「拿到响应头之前」的连接失败与 5xx / 超时（用户规则 2）
        .addInterceptor(RetryInterceptor(maxRetries = 3, baseDelayMs = 1000L))
        // 仅 debug 打印请求路径 / 状态码 / 耗时，便于区分网络超时与模型推理超时（用户规则 6）
        .apply { if (BuildConfig.DEBUG) addInterceptor(LlmLoggingInterceptor("LLM-BACKEND")) }
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(ApiConfig.AI_BASE_URL)   // 必须以 "/" 结尾，已在 ApiConfig 中保证
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val chatApi: EdenChatApi = retrofit.create(EdenChatApi::class.java)
}
