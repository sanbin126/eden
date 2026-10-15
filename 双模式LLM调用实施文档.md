# Eden 双模式 LLM 调用 · 实施文档

> 目标：同一个前端支持两种 LLM 调用模式，一键切换。
> - **backend（默认）**：走现有 `POST /api/chat` 代理链路，完全复用现有后端接口（后端零改动）。
> - **direct**：前端直连大模型（OpenAI 兼容 SSE），不启动后端，用于本地调试 prompt / 一次性问答。
>
> 硬性约束：**不改动、不修复现有 Agent 提问 / session / plans 等业务逻辑**。所有原文件改动均为「追加上层代码」或「仅替换 3 处调用标识符」，原有解析逻辑一行不动。

---

## 0. 现状核对（重要偏差说明）

在动手前，我按你的描述逐项核对了仓库，有一处需要你确认：

| 你的描述 | 仓库实际情况 |
| --- | --- |
| eden-backend 含 `sessions.py / context.py / plans.py / mcp` | ❌ 全项目 Grep / Glob 均**找不到这些文件**。实际后端是 [backend/app/main.py](file:///c:/Users/suwei/Desktop/eden/backend/app/main.py) 一个 FastAPI **无状态代理**（只转发到火山方舟），另有 [eden-backend.zip](file:///c:/Users/suwei/Desktop/eden/eden-backend.zip)（其内也只有 `app/main.py`、`Dockerfile`、`requirements.txt`）。 |

**结论**：现有"后端模式"= 复用 `POST /api/chat` 这个无状态代理。前端真正的"Agent 逻辑"（人设拼接、历史裁剪、记忆压缩）全部在客户端 [ChatSession.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/chat/ChatSession.kt) 的 `buildPayload()` 里 —— 这反而是好事：直连模式可以**复用同一份 payload**，Agent 逻辑天然零改动。

> 如果你口中的那套有 `sessions/context/plans` 的服务确实存在（只是没进这个仓库），请告诉我它的地址和接口，我再调整 backend 分支的指向。

---

## 1. 方案评估结论

**方案可行，属低风险纯增量改造。** 理由：

1. 现有 3 个 LLM 调用点全部集中在 [ChatRepository.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/data/ChatRepository.kt)（L88 非流式、L107 流式、L242 记忆压缩），只要在这 3 处把 `ApiClient.chatApi` 换成新的统一入口 `LlmClient`，路由逻辑就完成了。
2. 直连与后端在**流式协议上有差异**（后端是 `data: "<JSON字符串>"`，DeepSeek 是 `data: {"choices":[...]}`）。解决方案：在直连侧做**归一化**，把上游输出转成后端同款 SSE，让 `ChatRepository.requestReplyStream` 的旧解析代码**完全不用动**。
3. 密钥安全：直连 Key 放 `local.properties`（已 gitignore）→ BuildConfig；运行时再用 `BuildConfig.DEBUG` 兜底，release 包强制回退 backend。

---

## 2. 改动清单总览

### 2.1 新增文件（3 个）

| 文件 | 作用 |
| --- | --- |
| `app/src/main/java/com/example/eden/ui/theme/LlmModeManager.kt` | 全局模式配置（内存缓存 + SharedPreferences 持久化；release 强制回退 backend） |
| `app/src/main/java/com/example/eden/ui/theme/DirectLlmClient.kt` | 直连大模型客户端 + 流式归一化 + 重试/超时/日志 |
| `app/src/main/java/com/example/eden/ui/theme/LlmClient.kt` | 统一 LLM 请求入口，按模式路由到 backend / direct |

### 2.2 原有文件改动（全部为"追加"或"替换标识符"，不动旧逻辑）

| 文件 | 改动性质 | 具体位置 |
| --- | --- | --- |
| [app/build.gradle.kts](file:///c:/Users/suwei/Desktop/eden/app/build.gradle.kts) | 追加 | `defaultConfig` 末尾追加 3 个 `buildConfigField` |
| [EdenApp.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/EdenApp.kt) | 追加 1 行 | `onCreate()` 里 `AuthManager.init(this)` 之后追加 `LlmModeManager.init(this)` |
| [activity_settings.xml](file:///c:/Users/suwei/Desktop/eden/app/src/main/res/layout/activity_settings.xml) | 追加 | "通用"卡片里 `switch_greeting` 那一行之后追加一行开关 |
| [SettingsActivity.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/SettingsActivity.kt) | 追加 | 字段 + `initViews` + `loadSwitches` + `setupListeners` 各追加几行，并加 2 个 import |
| [ChatRepository.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/data/ChatRepository.kt) | **仅替换 3 处标识符** | L88、L107、L242：`ApiClient.chatApi` → `LlmClient`（导入 `LlmClient`，解析逻辑一行不动） |

> ✅ **没有任何一处修改现有的 Agent 状态机、参数收集、提问、session、plans 逻辑。**

---

## 3. 新增文件完整代码

### 3.1 `ui/theme/LlmModeManager.kt`（新增）

```kotlin
package com.example.eden.ui.theme

import android.content.Context
import com.example.eden.BuildConfig

/** 全局 LLM 调用模式。 */
enum class LlmMode { BACKEND, DIRECT }

/**
 * 全局 LLM 模式管理器。
 *
 * - 内存 @Volatile 缓存，供网络层高频读取（不查磁盘）。
 * - SharedPreferences 持久化，重启后保留用户选择。
 * - 安全兜底：release 包（非 DEBUG）永远回退 [LlmMode.BACKEND]，
 *   防止直连密钥被误打进正式包后仍被启用。
 */
object LlmModeManager {

    private const val PREFS_NAME = "llm_mode_prefs"
    private const val KEY_MODE = "llm_mode"

    @Volatile
    private var cached: LlmMode = LlmMode.BACKEND

    /** 在 Application.onCreate 中调用一次，把持久化值载入内存。 */
    fun init(context: Context) {
        cached = read(context)
    }

    /** 当前生效模式。 */
    fun current(): LlmMode = cached

    fun isDirect(): Boolean = cached == LlmMode.DIRECT

    /** 切换模式并持久化。 */
    fun set(context: Context, mode: LlmMode) {
        cached = mode
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.name)
            .apply()
    }

    private fun read(context: Context): LlmMode {
        val saved = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MODE, null)
        val mode = runCatching { saved?.let { LlmMode.valueOf(it) } }.getOrNull() ?: LlmMode.BACKEND
        // release 包禁用直连
        return if (mode == LlmMode.DIRECT && !BuildConfig.DEBUG) LlmMode.BACKEND else mode
    }
}
```

### 3.2 `ui/theme/LlmClient.kt`（新增）

```kotlin
package com.example.eden.ui.theme

import okhttp3.ResponseBody

/**
 * 统一 LLM 请求入口。
 *
 * 所有上层业务（对话 / 记忆压缩等）只调用这里，由它按 [LlmModeManager] 的当前模式
 * 路由到后端代理或直连大模型。上层不感知模式差异。
 */
object LlmClient {

    suspend fun chat(request: ChatRequest): ChatResponse =
        when (LlmModeManager.current()) {
            LlmMode.BACKEND -> BackendGateway.chat(request)
            LlmMode.DIRECT -> DirectGateway.chat(request)
        }

    suspend fun chatStream(request: ChatRequest): ResponseBody =
        when (LlmModeManager.current()) {
            LlmMode.BACKEND -> BackendGateway.chatStream(request)
            LlmMode.DIRECT -> DirectGateway.chatStream(request)
        }
}

/** 后端代理分支：完全沿用现有 [ApiClient]，后端接口零改动。 */
private object BackendGateway {
    suspend fun chat(request: ChatRequest): ChatResponse = ApiClient.chatApi.chat(request)
    suspend fun chatStream(request: ChatRequest): ResponseBody = ApiClient.chatApi.chatStream(request)
}
```

### 3.3 `ui/theme/DirectLlmClient.kt`（新增）

```kotlin
package com.example.eden.ui.theme

import com.example.eden.BuildConfig
import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Streaming
import java.io.IOException
import java.util.concurrent.TimeUnit

// =====================================================================================
// ⚠️⚠️ 仅供本地开发自用，禁止打包上线 ⚠️⚠️
// 直连模式会把大模型 API Key 打进客户端包内，一旦发布极易被逆向窃取。
// 请勿把 LLM_DIRECT_API_KEY 写入正式包；release 构建已由 LlmModeManager 强制回退 backend。
// =====================================================================================

private val SSE_MEDIA: MediaType = "text/event-stream".toMediaType()
private val JSON_MEDIA: MediaType = "application/json".toMediaType()

private val gson = Gson()

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

    /** 非流式：一次性拿完整回复（用于记忆压缩等） */
    @POST("chat/completions")
    suspend fun chat(@Body request: OpenAiChatRequest): OpenAiChatResponse

    /** 流式：返回上游 SSE 原始响应体，由 [NormalizeBody] 逐行归一化 */
    @Streaming
    @POST("chat/completions")
    suspend fun chatStream(@Body request: OpenAiChatRequest): ResponseBody
}

private object DirectClient {

    // 直连客户端：超时整体放宽（用户规则 1），读超时 90s 覆盖模型首字/长推理延迟
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .addInterceptor(DirectAuthInterceptor())
        // 指数退避重试（用户规则 2）：500/超时自动重试，最多 3 次，间隔 1s / 2s / 4s
        .addInterceptor(DirectRetryInterceptor(maxRetries = 3, baseDelayMs = 1000L))
        // 仅在 debug 构建打印完整请求体 / 响应 / 耗时（用户规则 6），release 不打印避免泄露密钥
        .apply { if (BuildConfig.DEBUG) addInterceptor(DirectLoggingInterceptor()) }
        .build()

    val api: DeepSeekApi = Retrofit.Builder()
        .baseUrl(BuildConfig.LLM_DIRECT_BASE_URL)   // 必须以 "/" 结尾
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(DeepSeekApi::class.java)
}

/** 直连网关注入与后端同款契约：非流式返回 [ChatResponse]，流式返回归一化后的 SSE。 */
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
            NormalizeBody(upstream, gson)
        } catch (e: Exception) {
            "data: [ERROR] 直连请求失败：${e.message ?: "未知错误"}\n\n"
                .toResponseBody(SSE_MEDIA)
        }
    }

    /** 把后端 [ChatRequest] 原样映射成 OpenAI 兼容请求，复用现有 payload，业务零改动。 */
    private fun buildRequest(request: ChatRequest, stream: Boolean) = OpenAiChatRequest(
        model = BuildConfig.LLM_DIRECT_MODEL,
        messages = request.messages.map { OpenAiMessage(it.role, it.content) },
        stream = stream
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

/**
 * 指数退避重试（与 AuthApi.kt 内的 RetryInterceptor 同款实现；因它是 file-private 无法复用，故此处复制）。
 * 仅重试可安全重放的失败：读/写超时、500/502/503/504。4xx 为确定性错误，直接返回。
 */
private class DirectRetryInterceptor(
    private val maxRetries: Int,
    private val baseDelayMs: Long
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var attempt = 0
        var lastError: IOException? = null
        while (true) {
            try {
                val response = chain.proceed(request)
                val retryable = response.code == 500 || response.code == 502 ||
                    response.code == 503 || response.code == 504
                if (!retryable || attempt >= maxRetries) return response
                response.close()
            } catch (e: IOException) {
                lastError = e
                if (attempt >= maxRetries) throw e
            }
            attempt++
            try {
                Thread.sleep(baseDelayMs shl (attempt - 1))   // 1s、2s、4s…
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw lastError ?: IOException("请求被中断")
            }
        }
    }
}

/** 仅 debug 生效：打印请求体、响应码、耗时，便于区分网络超时 / 模型推理超时。 */
private class DirectLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request: Request = chain.request()
        val start = System.currentTimeMillis()
        val response = chain.proceed(request)
        val cost = System.currentTimeMillis() - start
        val url = request.url.encodedPath
        println("[LLM-DIRECT] $url code=${response.code} cost=${cost}ms")
        return response
    }
}
```

> 说明：`ResponseBody` 的流在 `ChatRepository` 里用 `body.use { }` 关闭，会级联关闭 `NormalizeSource` → `upstream`，无连接泄漏。

---

## 4. 原有文件改动（追加 / 替换）

### 4.1 `app/build.gradle.kts`（追加 3 个 buildConfigField）

在 [app/build.gradle.kts](file:///c:/Users/suwei/Desktop/eden/app/build.gradle.kts) 的 `defaultConfig { ... }` 里，找到现有 `AI_BASE_URL` 那段（约 L48-51），在它**后面**追加：

```kotlin
        // ===== 直连模式（仅本地调试自用，禁止打入正式包）=====
        // 直连大模型 Base URL，需以 "/" 结尾。本项目使用火山方舟（ARK）OpenAI 兼容接入点。
        // 注意：本账号/模型实测 /api/v1/ 可用，/api/v3/ 不可用，勿随意改。
        buildConfigField(
            "String", "LLM_DIRECT_BASE_URL",
            "\"${secretOr("EDEN_LLM_DIRECT_BASE_URL", "https://ark.cn-beijing.volces.com/api/v1/")}\""
        )

        // 直连 API Key：只在 local.properties 里配置（已 gitignore），不要提交到仓库。
        buildConfigField(
            "String", "LLM_DIRECT_API_KEY",
            "\"${secretOr("EDEN_LLM_DIRECT_API_KEY", "")}\""
        )

        // 直连模型名 / 推理接入点 ID。
        buildConfigField(
            "String", "LLM_DIRECT_MODEL",
            "\"${secretOr("EDEN_LLM_DIRECT_MODEL", "doubao-seed-2-0-lite-260428")}\""
        )
```

本地调试时，在项目根目录 `local.properties` 追加（**该文件不进 git**，已按你的实际 Key 填好）：

```properties
EDEN_LLM_DIRECT_API_KEY=ce822122-64a5-425d-a59e-11b121a86f41
EDEN_LLM_DIRECT_BASE_URL=https\://ark.cn-beijing.volces.com/api/v1/
EDEN_LLM_DIRECT_MODEL=doubao-seed-2-0-lite-260428
```

### 4.2 `EdenApp.kt`（追加 1 行 + 1 个 import）

在 [EdenApp.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/EdenApp.kt) `onCreate()` 中，`AuthManager.init(this)` 之后追加一行：

```kotlin
        AuthManager.init(this)
        // 载入持久化的 LLM 调用模式（backend / direct）
        LlmModeManager.init(this)
```

并在文件顶部 import 区追加：

```kotlin
import com.example.eden.ui.theme.LlmModeManager
```

### 4.3 `activity_settings.xml`（追加一行开关）

在 [activity_settings.xml](file:///c:/Users/suwei/Desktop/eden/app/src/main/res/layout/activity_settings.xml) 中，找到 `switch_greeting` 所在的 `LinearLayout`（约 L388-413，以 `</LinearLayout>` 结束于 L413），在它的**闭合标签之后、卡片内层 `</LinearLayout>`（约 L414）之前**追加：

```xml
                    <View
                        android:layout_width="match_parent"
                        android:layout_height="1dp"
                        android:layout_marginStart="52dp"
                        android:background="#F0F0F0" />

                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="56dp"
                        android:gravity="center_vertical"
                        android:orientation="horizontal"
                        android:paddingHorizontal="16dp">

                        <ImageView
                            android:layout_width="22dp"
                            android:layout_height="22dp"
                            android:src="@drawable/ic_shield" />

                        <LinearLayout
                            android:layout_width="0dp"
                            android:layout_height="wrap_content"
                            android:layout_marginStart="14dp"
                            android:layout_weight="1"
                            android:orientation="vertical">

                            <TextView
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:text="LLM 调用模式"
                                android:textColor="#333333"
                                android:textSize="15sp" />

                            <TextView
                                android:id="@+id/tv_llm_mode_hint"
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:layout_marginTop="2dp"
                                android:text="后端代理"
                                android:textColor="#999999"
                                android:textSize="12sp" />
                        </LinearLayout>

                        <androidx.appcompat.widget.SwitchCompat
                            android:id="@+id/switch_llm_mode"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content" />
                    </LinearLayout>
```

> 复用现有 `SwitchCompat` 风格与 `ic_shield` 图标，无需新增资源。

### 4.4 `SettingsActivity.kt`（追加字段与逻辑 + 2 个 import）

在 [SettingsActivity.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/SettingsActivity.kt) 中，四处各追加：

**(1) 顶部 import 追加：**

```kotlin
import com.example.eden.ui.theme.LlmMode
import com.example.eden.ui.theme.LlmModeManager
```

**(2) 字段声明（在 `switchGreeting` 字段后）：**

```kotlin
    private lateinit var switchLlmMode: SwitchCompat
    private lateinit var tvLlmModeHint: TextView
```

**(3) `initViews()` 中追加：**

```kotlin
        switchLlmMode = findViewById(R.id.switch_llm_mode)
        tvLlmModeHint = findViewById(R.id.tv_llm_mode_hint)
```

**(4) `loadSwitches()` 中追加：**

```kotlin
        switchLlmMode.isChecked = LlmModeManager.current() == LlmMode.DIRECT
        refreshLlmModeHint()
```

**(5) `setupListeners()` 中追加：**

```kotlin
        switchLlmMode.setOnCheckedChangeListener { _, checked ->
            // release 包禁止直连：勾选时提示并回退
            if (checked && !BuildConfig.DEBUG) {
                switchLlmMode.isChecked = false
                Toast.makeText(this, "正式包已禁用直连模式", Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            LlmModeManager.set(this, if (checked) LlmMode.DIRECT else LlmMode.BACKEND)
            refreshLlmModeHint()
        }
```

**(6) 新增一个私有方法（放在 `loadSwitches()` 附近）：**

```kotlin
    private fun refreshLlmModeHint() {
        tvLlmModeHint.text = if (LlmModeManager.current() == LlmMode.DIRECT) {
            "直连大模型（仅本地调试）"
        } else {
            "后端代理（推荐）"
        }
    }
```

### 4.5 `ChatRepository.kt`（仅替换 3 处标识符）

在 [ChatRepository.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/data/ChatRepository.kt)：

**(1) import 区把 `ApiClient` 换成 `LlmClient`**（`ChatRequest` / `Message` 的 import 保留）：

```kotlin
import com.example.eden.ui.theme.LlmClient
import com.example.eden.ui.theme.ChatRequest
import com.example.eden.ui.theme.Message
```

**(2) L88（`requestReply`，非流式）** —— 只改标识符：

```kotlin
        val response = LlmClient.chat(ChatRequest(messages = messages))
```

**(3) L107（`requestReplyStream`，流式）** —— 只改标识符，下面的解析循环**一行不动**：

```kotlin
        val body = LlmClient.chatStream(ChatRequest(messages = messages, stream = true))
```

**(4) L242（`requestMemory`，记忆压缩，非流式）** —— 只改标识符：

```kotlin
        val response = LlmClient.chat(
            ChatRequest(
                messages = listOf(
                    Message(role = "system", content = MikuPersona.MEMORY_PROMPT),
                    Message(role = "user", content = input)
                )
            )
        )
```

> 关键点：`LlmClient.chatStream` 直连模式下返回的也是**后端同款 SSE 契约**（归一化过），所以下面的 `readLoop`、`[DONE]`、`[ERROR]`、`gson.fromJson(payload, String)` 逻辑**完全不用改**。

---

## 5. 自测步骤

### 5.1 编译

```powershell
cd c:\Users\suwei\Desktop\eden
.\gradlew.bat :app:assembleDebug
```

### 5.2 backend 模式（默认，回归验证）

1. 启动后端：`cd backend` → 按 README/`requirements.txt` 起 FastAPI（默认 8001）。
2. 确认 `local.properties` 未配 `EDEN_LLM_DIRECT_API_KEY`（或设置页开关关闭）。
3. 进设置页 → "LLM 调用模式" 应为**关闭**，副标题显示"后端代理（推荐）"。
4. 发一条消息：应正常流式回复；记忆压缩（连续聊够 40 条触发）也走后端。
5. 关掉后端再发消息：应报"无法连接服务器"类错误 —— 证明请求确实走了后端。

### 5.3 direct 模式

1. `local.properties` 配好 `EDEN_LLM_DIRECT_API_KEY`。
2. 重新编译安装。
3. 设置页打开"LLM 调用模式"开关，副标题变为"直连大模型（仅本地调试）"。
4. **关掉后端服务**（关键是证明不走后端）。
5. 发消息：应能正常逐字流式回复 —— 说明直连生效。
6. 断网测试：应报"直连请求失败：…"，并且**不再出现**"无法连接服务器（后端）"文案。
7. Key 填错测试：`data: [ERROR]` 分支被前端 `readLoop` 正确转成可重试的错误气泡。

### 5.4 模式切换即时生效

- 不必重启 App：切换开关后，下一条消息即按新模式发送（`LlmModeManager.cached` 是内存值）。

### 5.5 release 安全校验

```powershell
.\gradlew.bat :app:assembleRelease
```

- 装 release 包 → 设置页开关应**无法勾选**（弹"正式包已禁用直连模式"）。
- 即使 SharedPreferences 里残留 DIRECT，`LlmModeManager.read()` 也会回退 BACKEND。

### 5.6 用户规则对应自测点

| 规则 | 在哪体现 | 怎么验证 |
| --- | --- | --- |
| 1. 超时调大 | `DirectClient` read 90s / call 120s | 长回复不中断 |
| 2. 指数退避重试 3 次 | `DirectRetryInterceptor` | 让上游返回 500，日志可见 1s/2s/4s 重试 |
| 3. 裁剪上下文 | 复用现有 `ChatSession.MAX_HISTORY=24` | 历史消息不会无限增长 |
| 4. 换轻量模型 | `EDEN_LLM_DIRECT_MODEL` 可配 | 设为 flash 版对比延迟 |
| 5. 检查网络/DNS | 现有 `authErrorMessage` | 断网/改错误域名验证提示 |
| 6. 打印请求体/响应/耗时 | `DirectLoggingInterceptor`（仅 debug） | logcat 过滤 `LLM-DIRECT` |

---

## 6. 回滚方案

改动全是增量的，回滚极简单：

1. 删除 3 个新增文件：`LlmModeManager.kt`、`DirectLlmClient.kt`、`LlmClient.kt`。
2. `ChatRepository.kt` 把 3 处 `LlmClient` 改回 `ApiClient.chatApi`，import 改回 `ApiClient`。
3. 其余 4 个文件（gradle / EdenApp / xml / SettingsActivity）的追加段删除即可，原有逻辑未受影响。

或者更省事：把设置页开关保持关闭 → 运行时永远走 backend，直连代码不生效。

---

## 7. 已确认事项（原 3 个待拍板点的结论）

1. **后端**：用当前 `POST /api/chat` 无状态代理（仓库中不存在 sessions/context/plans 那套服务）。
2. **直连目标**：**火山方舟（ARK）**，非 DeepSeek。Key/端点取自项目 `local.properties`：
   - `EDEN_LLM_DIRECT_API_KEY=ce822122-64a5-425d-a59e-11b121a86f41`
   - `EDEN_LLM_DIRECT_BASE_URL=https://ark.cn-beijing.volces.com/api/v1/`（实测 v1 可用，v3 不可用）
   - `EDEN_LLM_DIRECT_MODEL=doubao-seed-2-0-lite-260428`
   - 已用该 Key 实测：非流式与流式（SSE）均返回正常，`delta.content` 解析无误（该模型会先输出 `reasoning_content`，解析器会自动跳过，只显示正式回复）。
3. **切换 UI**：设置页"通用"卡片内开关。

代码已按上述结论全部落地并通过 `assembleDebug` 编译。
