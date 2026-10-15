package com.example.eden.ui.theme

import android.util.Log
import com.example.eden.BuildConfig
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.random.Random

/**
 * 指数退避重试拦截器（含抖动）。
 *
 * 只重试「可安全重放」的失败：连接 / 读写超时，以及 408 / 429 / 500 / 502 / 503 / 504。
 * 4xx（401/403/404 等）是确定性错误，重试无意义，直接返回。
 *
 * 注意：拦截器只能兜住「拿到响应头之前」的失败。流式接口若上游已开始吐字后中断，
 * 这里无法感知（body 是惰性读取的）；那种情况由 ChatRepository 在 Flow 层按
 * 「是否已产出增量」判断是否值得重试，避免重复输出。
 */
class RetryInterceptor(
    private val maxRetries: Int = 3,
    private val baseDelayMs: Long = 1000L
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var attempt = 0
        var lastError: IOException? = null
        while (true) {
            try {
                val response = chain.proceed(request)
                if (!isRetryableCode(response.code) || attempt >= maxRetries) return response
                // 需要重试：先关掉本次响应体，避免连接泄漏
                response.close()
            } catch (e: IOException) {
                lastError = e
                if (attempt >= maxRetries) throw e
            }
            attempt++
            try {
                // 1s、2s、4s… 指数退避，叠加抖动
                Thread.sleep(delayMs(attempt))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw lastError ?: IOException("请求被中断")
            }
        }
    }

    /**
     * base * 2^(attempt-1)，再叠加 0~25% 抖动。
     * 抖动用于打散多个并发请求的重试时刻，避免同一瞬间一起重试把后端打爆。
     */
    private fun delayMs(attempt: Int): Long {
        val base = baseDelayMs shl (attempt - 1)
        return base + Random.nextLong(0, base / 4 + 1)
    }

    private fun isRetryableCode(code: Int): Boolean =
        code == 408 || code == 429 || code in 500..599
}

/**
 * 仅 debug 生效：打印请求路径、响应码与到响应头的耗时，便于区分网络超时 / 模型推理超时。
 *
 * 对 `@Streaming` 接口只能测到「首个响应头」的耗时，拿不到整段流式时长；
 * 整段耗时 / 首帧延迟由 ChatRepository 在 Flow 层单独埋点。
 */
class LlmLoggingInterceptor(private val tag: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request: Request = chain.request()
        val start = System.currentTimeMillis()
        val response = chain.proceed(request)
        val cost = System.currentTimeMillis() - start
        if (BuildConfig.DEBUG) {
            Log.d(tag, "${request.url.encodedPath} code=${response.code} cost=${cost}ms")
        }
        return response
    }
}
