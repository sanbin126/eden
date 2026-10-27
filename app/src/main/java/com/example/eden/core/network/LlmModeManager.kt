package com.example.eden.core.network


import com.example.eden.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 全局 LLM 调用模式。 */
enum class LlmMode { BACKEND, DIRECT }

/**
 * 全局 LLM 模式：自动判定，无需用户手动切换。
 *
 * 判定规则（按优先级）：
 *  1. release 包（非 DEBUG）永远走 [LlmMode.BACKEND]，防止直连密钥进入正式包后被启用。
 *  2. 未配置直连 Key → [LlmMode.BACKEND]（没有直连能力）。
 *  3. 探测后端存活接口：通 → [LlmMode.BACKEND]；不通 → [LlmMode.DIRECT]。
 *
 * 探测结果做短时缓存，避免每次对话都发一次探活：
 *  - 后端可用时缓存 60s；不可用时缓存 15s（让后端重启后能较快恢复）。
 */
object LlmModeManager {

    private const val OK_TTL_MS = 60_000L
    private const val FAIL_TTL_MS = 15_000L

    @Volatile
    private var cachedMode: LlmMode = LlmMode.BACKEND

    @Volatile
    private var cacheAtMs: Long = 0L

    @Volatile
    private var cacheSaysBackendUp: Boolean = false

    /** 探活专用客户端：短超时，连不上即判定后端不可用。 */
    private val probeClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .build()

    /** 解析当前应使用的模式。可在任意协程调用。 */
    suspend fun resolve(): LlmMode {
        // 1) 正式包永远走后端
        if (!BuildConfig.DEBUG) return LlmMode.BACKEND
        // 2) 没有直连 Key，无法直连
        if (BuildConfig.LLM_DIRECT_API_KEY.isBlank()) return LlmMode.BACKEND

        // 3) 命中未过期缓存
        val now = System.currentTimeMillis()
        val ttl = if (cacheSaysBackendUp) OK_TTL_MS else FAIL_TTL_MS
        if (cacheAtMs != 0L && now - cacheAtMs < ttl) return cachedMode

        // 4) 探活后端，决定本轮的最终模式
        val backendUp = withContext(Dispatchers.IO) { probeBackend() }
        val mode = if (backendUp) LlmMode.BACKEND else LlmMode.DIRECT
        synchronized(this) {
            cacheSaysBackendUp = backendUp
            cachedMode = mode
            cacheAtMs = System.currentTimeMillis()
        }
        return mode
    }

    /** 后端存活探针 `/health` 返回 2xx 即视作「后端在线」。 */
    private fun probeBackend(): Boolean = try {
        val url = ApiConfig.AI_BASE_URL + "health"   // AI_BASE_URL 已保证以 "/" 结尾
        val request = Request.Builder().url(url).get().build()
        probeClient.newCall(request).execute().use { it.isSuccessful }
    } catch (e: Exception) {
        false
    }

    /**
     * 当前模式的只读展示名，供设置页显示（用户不可切换）。
     * 会走一次探活，命中缓存时几乎立即返回。
     */
    suspend fun currentModeLabel(): String = when (resolve()) {
        LlmMode.BACKEND -> "后端模式 · 可联网"
        LlmMode.DIRECT -> "直连模式 · 不可联网"
    }
}
