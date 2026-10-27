package com.example.eden.data.repository


import com.example.eden.BuildConfig
import com.example.eden.data.local.ChatMemory
import com.example.eden.data.local.ChatMemoryEntity
import com.example.eden.data.local.ChatMessageEntity
import com.example.eden.data.local.EdenDatabase
import com.example.eden.domain.model.ChatSession
import android.content.Context
import android.util.Log
import com.example.eden.domain.model.ChatMessage
import com.example.eden.core.storage.EdenStorage
import com.example.eden.domain.model.MessageType
import com.example.eden.core.session.SessionManager
import com.example.eden.core.session.SyncKeys
import com.example.eden.domain.character.MikuPersona
import com.example.eden.core.network.LlmClient
import com.example.eden.core.network.ChatRequest
import com.example.eden.core.network.LlmException
import com.example.eden.core.network.Message
import com.example.eden.core.network.parseSseError
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.random.Random

/**
 * 聊天数据仓库：对上层（ViewModel）屏蔽「Room 存哪里」「AI 请求怎么发」这些细节。
 *
 * 同时负责把旧版本存在 SharedPreferences 里的 JSON 聊天记录一次性迁移进 Room，
 * 迁移成功后删除旧键，保证老用户升级后不丢聊天记录。
 *
 * 长期记忆的读写与压缩也收在这里：压缩必须基于数据库里的**全量**聊天记录，
 * 而不是界面上被截断的消息列表，否则窗口外的旧对话永远进不了记忆。
 */
class ChatRepository(context: Context) {

    private val appContext = context.applicationContext
    private val gson = Gson()

    // DAO 懒取：本地库按账号分区，切号后 EdenDatabase.getInstance 会指向新账号的库，
    // 因此这里不能构造期缓存，否则会一直读写上一个账号的分区。
    private val dao get() = EdenDatabase.getInstance(appContext).chatMessageDao()
    private val memoryDao get() = EdenDatabase.getInstance(appContext).chatMemoryDao()

    /** 读取某个角色的全部聊天记录（首次调用时会尝试迁移旧数据） */
    suspend fun load(characterId: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        migrateLegacyIfNeeded(characterId)
        dao.queryByCharacter(characterId).map { it.toModel() }
    }

    /**
     * 该角色最早一条消息的时间戳。用于"在一起第 X 天"的回退起点（用户未设纪念日时）。
     * 无记录返回 null。
     */
    suspend fun firstChatTime(characterId: String): Long? = withContext(Dispatchers.IO) {
        migrateLegacyIfNeeded(characterId)
        dao.firstMessageTimestamp(characterId)
    }

    /** 追加一条消息，返回写入后的自增主键 */
    suspend fun append(characterId: String, message: ChatMessage): Long =
        withContext(Dispatchers.IO) {
            // 先迁移旧数据再写入：后台问候可能早于聊天页写入，
            // 若先插入会令 count>0，导致旧记录在 load() 迁移时被跳过并删除
            migrateLegacyIfNeeded(characterId)
            val id = dao.insert(message.toEntity(characterId))
            SessionManager.notifyChanged(appContext, SyncKeys.chat(characterId))
            id
        }

    /** 批量追加 */
    suspend fun appendAll(characterId: String, messages: List<ChatMessage>) =
        withContext(Dispatchers.IO) {
            migrateLegacyIfNeeded(characterId)
            dao.insertAll(messages.map { it.toEntity(characterId) })
            SessionManager.notifyChanged(appContext, SyncKeys.chat(characterId))
        }

    /** 清空某个角色的聊天记录与长期记忆 */
    suspend fun clear(characterId: String) = withContext(Dispatchers.IO) {
        // 同时清掉可能残留的旧数据，避免下次 load() 又把旧记录迁回来
        migrateLegacyIfNeeded(characterId)
        dao.deleteByCharacter(characterId)
        memoryDao.deleteByCharacter(characterId)
        SessionManager.notifyChanged(appContext, SyncKeys.chat(characterId))
    }

    /**
     * 请求大模型回复（走自建后端代理，密钥不落在客户端）。
     *
     * 非流式请求可安全重放，故对网络失败 / 超时 / 服务端异常做指数退避重试，
     * 最多 [MAX_RETRIES] 次；4xx 等确定性错误（[LlmException.retryable] 为 false）直接抛出。
     * 全部尝试失败后抛出最后一次异常，由上层转成用户可见的错误提示 + 重试入口。
     */
    suspend fun requestReply(messages: List<Message>): String = withContext(Dispatchers.IO) {
        var attempt = 0
        var lastError: Exception? = null
        while (attempt <= MAX_RETRIES) {
            try {
                val response = LlmClient.chat(ChatRequest(messages = messages))
                val reply = response.data?.reply
                if (response.code != 0 || reply.isNullOrBlank()) {
                    throw LlmException(response.message ?: "服务返回异常（code=${response.code}）")
                }
                return@withContext reply
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (!isRetryable(e) || attempt >= MAX_RETRIES) throw e
                Log.w(TAG, "requestReply 第${attempt + 1}次失败，${retryDelayMs(attempt)}ms 后重试：${e.message}")
                delay(retryDelayMs(attempt))
                attempt++
            }
        }
        throw lastError ?: IOException("请求失败")
    }

    /**
     * 流式请求大模型回复：逐段吐出增量文本，调用方拼装后实时渲染，首字延迟显著低于非流式。
     *
     * SSE 协议（与 [server/main.py][_stream_upstream] 对应）：
     * - `data: <JSON 字符串>` ：一段增量，JSON 解码后即模型输出片段（可能含换行）。
     * - `data: [DONE]`        ：流结束。
     * - `data: [ERROR:<code>] <文案>`：上游异常，解析成 [LlmException] 抛给调用方转成重试入口。
     *
     * 重试策略：**只在本轮未吐出任何增量时重试**。流式一旦吐过字就无法整段重放——
     * 否则会把已展示的内容再输出一遍。故以 `emitted == 0` 作为唯一守卫，
     * 配合 `[LlmException.retryable]` 与 [MAX_RETRIES] 决定是否指数退避重试。
     *
     * 在 [Dispatchers.IO] 上读取响应体，避免阻塞主线程。
     */
    fun requestReplyStream(messages: List<Message>): Flow<String> = flow {
        var attempt = 0
        while (true) {
            var emitted = 0
            var firstDeltaMs = -1L
            val startedAt = System.currentTimeMillis()
            try {
                val body = LlmClient.chatStream(ChatRequest(messages = messages, stream = true))
                body.use { rb ->
                    val source = rb.source()
                    readLoop@ while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload.isEmpty()) continue
                        when {
                            payload == "[DONE]" -> break@readLoop
                            payload.startsWith("[ERROR") -> throw parseSseError(payload)
                            else -> {
                                val delta = runCatching { gson.fromJson(payload, String::class.java) }
                                    .getOrNull()
                                if (!delta.isNullOrEmpty()) {
                                    if (emitted == 0) firstDeltaMs = System.currentTimeMillis() - startedAt
                                    emitted++
                                    emit(delta)
                                }
                            }
                        }
                    }
                }
                if (BuildConfig.DEBUG) {
                    Log.d(
                        TAG,
                        "流式完成 第${attempt + 1}次尝试 增量${emitted}段 " +
                            "首帧${firstDeltaMs}ms 总耗时${System.currentTimeMillis() - startedAt}ms"
                    )
                }
                return@flow
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 已吐过字 / 属确定性错误 / 重试次数用尽 → 不再重试，直接上报
                if (emitted > 0 || !isRetryable(e) || attempt >= MAX_RETRIES) throw e
                Log.w(TAG, "流式第${attempt + 1}次未出字即失败，${retryDelayMs(attempt)}ms 后重试：${e.message}")
                delay(retryDelayMs(attempt))
                attempt++
            }
        }
    }.flowOn(Dispatchers.IO)

    /** 按主键删除单条记录（长按菜单删除 / 重新生成时抹掉旧回复）。id 为 0 表示尚未落库，直接跳过。 */
    suspend fun deleteMessage(id: Long) = withContext(Dispatchers.IO) {
        if (id != 0L) {
            dao.deleteById(id)
            SessionManager.notifyChanged(appContext, SyncKeys.chat(MikuPersona.ID))
        }
    }

    /** 清理某角色遗留的日期分隔线（旧版本落库产生）。没有删除任何记录时不触发同步。 */
    suspend fun deleteDividers(characterId: String) = withContext(Dispatchers.IO) {
        migrateLegacyIfNeeded(characterId)
        if (dao.deleteDividers(characterId) > 0) {
            SessionManager.notifyChanged(appContext, SyncKeys.chat(characterId))
        }
    }

    // ==================== 长期记忆 ====================

    /** 读取某个角色的长期记忆，没有则返回空记忆 */
    suspend fun loadMemory(characterId: String): ChatMemory = withContext(Dispatchers.IO) {
        readMemory(characterId)
    }

    /**
     * 若未纳入记忆的对话已经攒够阈值，就把其中较早的部分压缩进长期记忆。
     *
     * 返回更新后的记忆；无需压缩或压缩失败时返回 null（记忆只是锦上添花，
     * 任何异常都不应影响正常聊天）。
     */
    suspend fun summarizeIfNeeded(characterId: String): ChatMemory? = withContext(Dispatchers.IO) {
        val memory = readMemory(characterId)
        val pending = dao.queryPendingMemory(characterId, memory.summarizedUntilId).map { it.toModel() }
        if (pending.size <= SUMMARY_TRIGGER) return@withContext null

        // 保留最近 RAW_KEEP 条原文不做摘要，让短期上下文依旧连贯
        val toCompress = pending.dropLast(RAW_KEEP)
        if (toCompress.isEmpty()) return@withContext null

        val updated = try {
            requestMemory(memory, toCompress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext null
        }

        memoryDao.upsert(updated.toEntity(characterId))
        SessionManager.notifyChanged(appContext, SyncKeys.chat(characterId))
        updated
    }

    // ==================== 与后端的业务数据同步（导出 / 导入） ====================

    /**
     * 导出某角色的聊天记录与长期记忆为 JSON（供上传后端）。
     *
     * 形如 `{"messages":[{id,content,type,timestamp,isRead}...],"memory":{summary,facts,summarizedUntilId}}`。
     * 保留消息 id，便于恢复后 [ChatMemory.summarizedUntilId] 仍能对上新消息。
     */
    suspend fun exportJson(characterId: String): String = withContext(Dispatchers.IO) {
        migrateLegacyIfNeeded(characterId)
        val messages = dao.queryByCharacter(characterId).map {
            ChatMessageDto(it.id, it.content, it.type, it.timestamp, it.isRead)
        }
        val memory = readMemory(characterId).let {
            ChatMemoryDto(it.summary, it.facts, it.summarizedUntilId)
        }
        gson.toJson(ChatDocDto(messages, memory))
    }

    /** 用后端拉取到的 JSON 覆盖本地该角色的聊天记录与长期记忆（不做上传回调，避免回环）。 */
    suspend fun importJson(characterId: String, json: String) = withContext(Dispatchers.IO) {
        val doc = runCatching { gson.fromJson(json, ChatDocDto::class.java) }.getOrNull()
            ?: return@withContext
        dao.deleteByCharacter(characterId)
        memoryDao.deleteByCharacter(characterId)
        if (doc.messages.isNotEmpty()) {
            dao.insertAll(
                doc.messages.map {
                    ChatMessageEntity(
                        id = it.id,
                        characterId = characterId,
                        content = it.content,
                        type = it.type,
                        timestamp = it.timestamp,
                        isRead = it.isRead
                    )
                }
            )
        }
        doc.memory?.let {
            memoryDao.upsert(
                ChatMemoryEntity(
                    characterId = characterId,
                    summary = it.summary,
                    facts = gson.toJson(it.facts),
                    summarizedUntilId = it.summarizedUntilId,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    private suspend fun requestMemory(memory: ChatMemory, messages: List<ChatMessage>): ChatMemory {
        val input = buildString {
            appendLine("【已有记忆 · 摘要】")
            appendLine(memory.summary.ifBlank { "（暂无）" })
            appendLine("【已有记忆 · 事实】")
            appendLine(memory.facts.joinToString("\n") { "- $it" }.ifBlank { "（暂无）" })
            appendLine("【新增对话】")
            append(transcript(messages))
        }
        // 复用 requestReply 的重试逻辑，记忆压缩同样享有网络抖动下的自动重试
        val reply = requestReply(
            listOf(
                Message(role = "system", content = MikuPersona.MEMORY_PROMPT),
                Message(role = "user", content = input)
            )
        )
        return parseMemory(reply, memory, messages.last().id)
    }

    /** 把对话原文拼成纯文本；过长时只取末尾，避免超过后端单条消息长度限制 */
    private fun transcript(messages: List<ChatMessage>): String {
        val text = messages.joinToString("\n") { msg ->
            val who = if (msg.type == MessageType.TYPE_SENT) "用户" else MikuPersona.NAME
            "$who：${msg.content}"
        }
        return if (text.length <= MAX_TRANSCRIPT_CHARS) text else text.takeLast(MAX_TRANSCRIPT_CHARS)
    }

    /**
     * 解析模型返回的 JSON 记忆。
     * 模型偶尔会夹带代码块或解释文字，这里只截取第一个 `{` 到最后一个 `}`；
     * 完全解析不出来时退化为「把整段当摘要」，事实沿用旧值，保证不丢记忆。
     */
    private fun parseMemory(raw: String, previous: ChatMemory, untilId: Long): ChatMemory {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return fallbackMemory(raw, previous, untilId)

        return runCatching {
            val obj = gson.fromJson(raw.substring(start, end + 1), JsonObject::class.java)
            val summary = obj.get("summary")?.asString.orEmpty().trim().take(MAX_SUMMARY_CHARS)
            val facts = obj.getAsJsonArray("facts")
                ?.mapNotNull { it.asString.trim().ifBlank { null } }
                ?.take(MAX_FACTS)
                .orEmpty()
            ChatMemory(
                summary = summary,
                // 模型若没返回事实，保留原有事实，避免一次异常输出把记忆清空
                facts = facts.ifEmpty { previous.facts },
                summarizedUntilId = untilId
            )
        }.getOrElse { fallbackMemory(raw, previous, untilId) }
    }

    private fun fallbackMemory(raw: String, previous: ChatMemory, untilId: Long) = ChatMemory(
        summary = raw.trim().take(MAX_SUMMARY_CHARS),
        facts = previous.facts,
        summarizedUntilId = untilId
    )

    private suspend fun readMemory(characterId: String): ChatMemory {
        val entity = memoryDao.query(characterId) ?: return ChatMemory.EMPTY
        return ChatMemory(
            summary = entity.summary,
            facts = decodeFacts(entity.facts),
            summarizedUntilId = entity.summarizedUntilId
        )
    }

    private fun decodeFacts(json: String): List<String> = runCatching {
        val type = object : TypeToken<List<String>>() {}.type
        gson.fromJson<List<String>>(json, type)
    }.getOrNull().orEmpty()

    private fun ChatMemory.toEntity(characterId: String) = ChatMemoryEntity(
        characterId = characterId,
        summary = summary,
        facts = gson.toJson(facts),
        summarizedUntilId = summarizedUntilId,
        updatedAt = System.currentTimeMillis()
    )

    // ==================== 旧数据迁移 ====================

    private suspend fun migrateLegacyIfNeeded(characterId: String) {
        val prefs = EdenStorage.prefs(appContext, LEGACY_PREFS)
        val key = "chat_$characterId"
        val json = prefs.getString(key, null) ?: return

        val legacy = runCatching {
            val type = object : TypeToken<List<ChatMessage>>() {}.type
            gson.fromJson<List<ChatMessage>>(json, type)
        }.getOrNull() ?: return

        // 数据库里已有记录时以数据库为准，仅清理旧键
        if (legacy.isNotEmpty() && dao.countByCharacter(characterId) == 0) {
            dao.insertAll(legacy.map { it.toEntity(characterId) })
        }
        prefs.edit().remove(key).apply()
    }

    // ==================== 模型转换 ====================

    private fun ChatMessage.toEntity(characterId: String) = ChatMessageEntity(
        characterId = characterId,
        content = content,
        type = type.name,
        timestamp = timestamp,
        isRead = isRead
    )

    private fun ChatMessageEntity.toModel() = ChatMessage(
        id = id,
        content = content,
        type = runCatching { MessageType.valueOf(type) }.getOrDefault(MessageType.TYPE_RECEIVED),
        timestamp = timestamp,
        isRead = isRead
    )

    // ==================== 重试辅助 ====================

    /**
     * 是否值得重试：
     * - [LlmException]：由解析出的上游状态码决定（5xx / 429 / 超时可重试，4xx 不重试）；
     * - [IOException]：连接失败、读写超时等网络问题，可重试；
     * - 其余（解析错误等确定性失败）不重试。
     */
    private fun isRetryable(e: Throwable): Boolean = when (e) {
        is LlmException -> e.retryable
        is IOException -> true
        else -> false
    }

    /** 指数退避 + 抖动：base * 2^attempt，再叠加 0~25% 抖动，打散并发重试时刻。 */
    private fun retryDelayMs(attempt: Int): Long {
        val base = RETRY_BASE_DELAY_MS shl attempt
        return base + Random.nextLong(0, base / 4 + 1)
    }

    private companion object {
        const val LEGACY_PREFS = "chat_history"

        /** 日志 tag，便于在 Logcat 里按 ChatRepo 过滤流式首帧 / 重试 / 总耗时 */
        const val TAG = "ChatRepo"

        /** 单次请求失败后的额外重试次数：首次 + [MAX_RETRIES] 次，最多 3 次尝试 */
        const val MAX_RETRIES = 2

        /** 重试基础间隔：1s、2s…（叠加抖动），与拦截器保持一致 */
        const val RETRY_BASE_DELAY_MS = 1000L

        /** 未纳入记忆的对话条数超过该值时，触发一次压缩（需大于 RAW_KEEP，否则会把仍在窗口内的消息重复摘要） */
        const val SUMMARY_TRIGGER = 80

        /** 压缩时保留最近这么多条原文不进摘要（与 ChatSession.MAX_HISTORY 保持一致） */
        const val RAW_KEEP = 50

        const val MAX_FACTS = 20
        const val MAX_SUMMARY_CHARS = 250

        /** 单次压缩送给模型的对话文本上限，避免超过后端单条消息的长度限制 */
        const val MAX_TRANSCRIPT_CHARS = 2600
    }
}

/** 聊天文档序列化用的 DTO（仅用于与后端同步，字段名与 [ChatMessageEntity] 对应）。 */
private data class ChatMessageDto(
    val id: Long,
    val content: String,
    val type: String,
    val timestamp: Long,
    val isRead: Boolean
)

private data class ChatMemoryDto(
    val summary: String,
    val facts: List<String>,
    val summarizedUntilId: Long
)

private data class ChatDocDto(
    val messages: List<ChatMessageDto>,
    val memory: ChatMemoryDto? = null
)
