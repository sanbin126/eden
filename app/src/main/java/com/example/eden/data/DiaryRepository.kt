package com.example.eden.data

import android.content.Context
import com.example.eden.EdenStorage
import com.example.eden.SyncManager
import com.example.eden.auth.SyncKeys
import com.example.eden.character.MikuPersona
import com.example.eden.ui.theme.ApiClient
import com.example.eden.ui.theme.ChatRequest
import com.example.eden.ui.theme.Message
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 日记数据仓库：对上层屏蔽「存哪里」「摘要怎么来」这些细节。
 *
 * 三件事：
 *  1. 用 Room 持久化日记，并支持按日期增量读取（聊天时只需"今天/昨天"）；
 *  2. 把旧版 SharedPreferences 里的日记一次性迁移进 Room，保证老用户不丢数据；
 *  3. 保存后调用 LLM 把日记压缩成一句话摘要，写入该行的 summary 列，
 *     供聊天时注入、让三玖自然地提起（失败保留本地兜底摘要，不影响保存本身）。
 *
 * 摘要与聊天长期记忆（[ChatMemoryEntity]）分开存放：日记更像"时效性素材"，
 * 只在写下的当天/次日注入，过期后不再反复打扰，也不会挤占长期记忆的配额。
 */
class DiaryRepository(context: Context) {

    private val appContext = context.applicationContext
    private val gson = Gson()

    // DAO 懒取：本地库按账号分区，切号后指向新账号的库，不能构造期缓存。
    private val dao get() = EdenDatabase.getInstance(appContext).diaryDao()

    /** 全部日记，新的在前 */
    suspend fun getAll(): List<DiaryEntry> = withContext(Dispatchers.IO) {
        migrateLegacyIfNeeded()
        dao.queryAll().map { it.toModel() }
    }

    /**
     * 最近 [days] 天内写过的日记（含今天、昨天…），新的在前。
     * 聊天页用它拉取"当天/昨天"的日记摘要注入上下文。
     */
    suspend fun recentEntries(days: Int = RECENT_DAYS): List<DiaryEntry> = withContext(Dispatchers.IO) {
        migrateLegacyIfNeeded()
        dao.querySince(dateBefore(days - 1)).map { it.toModel() }
    }

    /**
     * 保存一条日记，先用本地兜底摘要落库（保证任何情况下都有摘要可用），返回自增主键。
     * 保存失败会抛出异常，由调用方决定是否提示用户。
     */
    suspend fun save(entry: DiaryEntry): Long = withContext(Dispatchers.IO) {
        migrateLegacyIfNeeded()
        val id = dao.insert(entry.toEntity(summary = localSummary(entry)))
        SyncManager.notifyChanged(appContext, SyncKeys.DIARY)
        id
    }

    /**
     * 用 LLM 把日记压缩成一句话并回填到该行；失败静默（保留 save 时的本地兜底摘要）。
     * 属于增强能力，跑在后台，不阻塞保存与列表刷新。
     */
    suspend fun summarize(id: Long, entry: DiaryEntry) = withContext(Dispatchers.IO) {
        val summary = try {
            requestSummary(entry)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext
        }
        dao.updateSummary(id, summary)
        SyncManager.notifyChanged(appContext, SyncKeys.DIARY)
    }

    // ==================== 与后端的业务数据同步（导出 / 导入） ====================

    /** 导出全部日记为 JSON 数组字符串（供上传后端）。字段沿用旧格式，额外带上 summary。 */
    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        migrateLegacyIfNeeded()
        gson.toJson(dao.queryAll().map { it.toDto() })
    }

    /** 用后端拉取到的 JSON 覆盖本地日记（不做上传回调，避免回环）。 */
    suspend fun importJson(json: String) = withContext(Dispatchers.IO) {
        val type = object : TypeToken<List<DiaryDto>>() {}.type
        val dtos = runCatching { gson.fromJson<List<DiaryDto>>(json, type) }.getOrNull() ?: return@withContext
        dao.deleteAll()
        if (dtos.isNotEmpty()) {
            dao.insertAll(dtos.map { it.toEntity() })
        }
    }

    // ==================== 摘要 ====================

    /** LLM 压缩：把日记整理成一句第三人称事实，供角色稍后自然地关心用户 */
    private suspend fun requestSummary(entry: DiaryEntry): String {
        val input = buildString {
            appendLine("日期：${entry.date}")
            appendLine("心情：${entry.mood}")
            if (entry.tags.isNotEmpty()) appendLine("标签：${entry.tags.joinToString("、")}")
            appendLine("正文：${entry.content}")
        }
        val response = ApiClient.chatApi.chat(
            ChatRequest(
                messages = listOf(
                    Message(role = "system", content = MikuPersona.DIARY_SUMMARY_PROMPT),
                    Message(role = "user", content = input)
                )
            )
        )
        val reply = response.data?.reply
        if (response.code != 0 || reply.isNullOrBlank()) {
            throw IOException(response.message ?: "日记摘要失败（code=${response.code}）")
        }
        val line = reply.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?.removeSurrounding("\"")?.removeSurrounding("「", "」")
        if (line.isNullOrBlank()) throw IOException("日记摘要为空")
        return line.take(MAX_SUMMARY_CHARS)
    }

    /** 本地兜底摘要：不依赖网络，保证日记一定有可注入的一句话 */
    private fun localSummary(entry: DiaryEntry): String {
        val tail = entry.content.replace('\n', ' ').trim().take(LOCAL_SUMMARY_CHARS)
        return "心情${entry.mood}：$tail"
    }

    // ==================== 旧数据迁移 ====================

    private suspend fun migrateLegacyIfNeeded() {
        val prefs = EdenStorage.prefs(appContext, LEGACY_PREFS)
        val json = prefs.getString(LEGACY_KEY, null) ?: return

        val legacy = runCatching {
            val type = object : TypeToken<List<DiaryDto>>() {}.type
            gson.fromJson<List<DiaryDto>>(json, type)
        }.getOrNull() ?: return

        // 数据库里已有数据时以数据库为准，仅清理旧键
        if (legacy.isNotEmpty() && dao.count() == 0) {
            dao.insertAll(legacy.map { it.toEntity() })
        }
        prefs.edit().remove(LEGACY_KEY).apply()
    }

    // ==================== 模型转换 ====================

    private fun DiaryEntry.toEntity(summary: String) = DiaryEntity(
        date = date,
        mood = mood,
        moodEmoji = moodEmoji,
        tags = gson.toJson(tags),
        content = content,
        summary = summary,
        createdAt = createdAt
    )

    private fun DiaryEntity.toModel() = DiaryEntry(
        id = id,
        date = date,
        mood = mood,
        moodEmoji = moodEmoji,
        tags = decodeTags(tags),
        content = content,
        summary = summary,
        createdAt = createdAt
    )

    private fun DiaryEntity.toDto() = DiaryDto(
        date = date,
        mood = mood,
        moodEmoji = moodEmoji,
        tags = decodeTags(tags),
        content = content,
        summary = summary,
        createdAt = createdAt
    )

    private fun DiaryDto.toEntity() = DiaryEntity(
        date = date,
        mood = mood,
        moodEmoji = moodEmoji,
        tags = gson.toJson(tags),
        content = content,
        summary = summary,
        createdAt = createdAt
    )

    private fun decodeTags(json: String): List<String> = runCatching {
        val type = object : TypeToken<List<String>>() {}.type
        gson.fromJson<List<String>>(json, type)
    }.getOrNull().orEmpty()

    private fun dateBefore(daysBack: Int): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -daysBack)
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
    }

    private companion object {
        const val LEGACY_PREFS = "diary_store"
        const val LEGACY_KEY = "entries"

        /** 聊天注入时向前回看的天数：含今天，即"今天 + 昨天" */
        const val RECENT_DAYS = 2

        const val MAX_SUMMARY_CHARS = 60
        const val LOCAL_SUMMARY_CHARS = 30
    }
}

/**
 * 日记序列化用的 DTO：字段名与旧版 SharedPreferences / 后端存的历史 JSON 保持一致，
 * 因此旧数据（没有 summary 字段）也能被 Gson 以默认值解析。
 */
private data class DiaryDto(
    val date: String = "",
    val mood: String = "",
    val moodEmoji: String = "",
    val tags: List<String> = emptyList(),
    val content: String = "",
    val summary: String = "",
    val createdAt: Long = 0
)
