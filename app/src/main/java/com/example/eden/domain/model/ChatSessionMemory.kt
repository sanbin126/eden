package com.example.eden.domain.model

import android.util.Log
import com.example.eden.data.local.DiaryEntry
import com.example.eden.domain.character.MikuPersona
import com.example.eden.domain.memory.ExtractedFact
import com.example.eden.domain.memory.MemoryCategory
import com.example.eden.domain.memory.MemoryExtractor
import com.example.eden.domain.memory.MemoryLlmExtractor
import com.example.eden.feature.story.MilestoneRepository
import kotlinx.coroutines.CancellationException

/**
 * [ChatSession] 的「上下文注入」实现：长期记忆、日记、里程碑提示的构建与事实抽取。
 *
 * 由 ChatSession.kt 物理拆分而来，函数体逐行保持原样，仅把接收者显式写成 ChatSession。
 */

/** 把长期记忆拼成一条 system 消息；没有记忆时返回 null，避免注入空内容浪费 token */
internal fun ChatSession.memoryContext(): String? {
    if (memory.isEmpty) return null
    return buildString {
        appendLine("以下是你此前陪伴这位用户时积累的长期记忆，请在回复时自然地运用（称呼、偏好、约定等），但不要直接复述这段内容：")
        if (memory.facts.isNotEmpty()) {
            appendLine("【关于用户的事实】")
            memory.facts.forEach { appendLine("- $it") }
        }
        if (memory.summary.isNotBlank()) {
            appendLine("【过往聊天摘要】")
            appendLine(memory.summary)
        }
    }.trim()
}

/**
 * 结构化长期记忆注入：按当前话题检索出最相关的几条，拼成一条 system 消息。
 *
 * identity 类（名字、年龄、身份）永远在场；其余按相关度 + 重要度 + 新鲜度 + 频次筛选。
 * 用户追问「你还记得吗」时走全量召回。检索失败静默降级，不能影响聊天。
 */
internal suspend fun ChatSession.structuredMemoryContext(source: List<ChatMessage>): String? {
    val recent = source
        .filter { it.type == MessageType.TYPE_SENT || it.type == MessageType.TYPE_RECEIVED }
        .takeLast(MEMORY_QUERY_MESSAGES)
    val query = recent.joinToString("\n") { it.content }
    if (query.isBlank()) return null

    val recall = MemoryExtractor.isExplicitRecall(
        recent.lastOrNull { it.type == MessageType.TYPE_SENT }?.content.orEmpty()
    )
    val memories = try {
        longTermMemoryRepository.retrieve(MikuPersona.ID, query, forceAll = recall)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "长期记忆检索失败：${e.message}")
        return null
    }
    if (memories.isEmpty()) return null

    return buildString {
        appendLine("【你记得关于对方的事】")
        memories.forEach { item ->
            val origin = if (item.category == MemoryCategory.IDENTITY.value) {
                "他亲口告诉你的"
            } else {
                relativeTime(item.lastMentionedAt)
            }
            appendLine("- ${item.content}（$origin）")
        }
        appendLine()
        appendLine("你可以自然地使用这些记忆，但不要生硬罗列。只有当话题相关时才提及，提及时要像“自然想起来”，而不是“查数据库”。")
        appendLine("如果记忆与当前对话无关，不要强行提。")
        if (recall) {
            appendLine("用户正在追问以前说过的事，可以自然地回应「你之前提过……」，但只使用上面记录过的内容，不要编造。")
        }
    }.trim()
}

/**
 * 抽取用户消息里的稳定事实并落库。
 *
 * 规则抽取先跑（零成本、准确），再由 LLM 补充长尾事实；两边按 `key` 合并，
 * 规则命中优先，避免模型改写同一事实导致重复记忆。失败静默，取消异常照常透传。
 */
internal suspend fun ChatSession.rememberUserMessage(text: String, sourceMessageId: Long) {
    if (sourceMessageId <= 0L) return
    try {
        val merged = LinkedHashMap<String, ExtractedFact>()
        MemoryExtractor.extract(text).forEach { merged[it.key] = it }
        MemoryLlmExtractor.extract(text).forEach { merged.putIfAbsent(it.key, it) }
        if (merged.isNotEmpty()) {
            longTermMemoryRepository.remember(MikuPersona.ID, merged.values.toList(), sourceMessageId)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "长期记忆抽取失败：${e.message}")
    }
}

/** 把时间戳换成「两周前提过」这类自然表述，供注入时提示记忆的新鲜度。 */
internal fun ChatSession.relativeTime(timestamp: Long): String {
    val days = ((System.currentTimeMillis() - timestamp).coerceAtLeast(0L)) / DAY_MS
    return when {
        days <= 0L -> "今天提过"
        days == 1L -> "昨天提过"
        days < 7L -> "${days}天前提过"
        days < 30L -> "${days / 7}周前提过"
        days < 365L -> "${days / 30}个月前提到"
        else -> "很久以前提到"
    }
}

/**
 * 好感度刚刚突破里程碑时，注入一条一次性的关系升温提示，让角色"感知"到关系更近了一步。
 * 用完即清（[MilestoneRepository.consumeAiHint]），因此每段关系只会自然升温一次，不会反复强调。
 */
internal fun ChatSession.milestoneContext(): String? {
    val milestone = MilestoneRepository.consumeAiHint(appContext) ?: return null
    return "【系统提示】你与用户的好感度刚刚突破到 $milestone，此刻你比平时更愿意向对方袒露一点真心，" +
        "但仍然保持害羞、含蓄的语气。不要直接提及好感度或数值。"
}

/**
 * 读取最近（今天 / 昨天）写过的日记摘要，拼成一段 system 提示备用。
 * 只在用户主动发起对话时刷新，让三玖能在聊天中自然提起，而不是无条件主动打扰。
 * 读取失败静默（日记只是锦上添花，不能影响正常聊天，但取消异常必须透传）。
 */
internal suspend fun ChatSession.refreshDiaryContext() {
    diaryContext = try {
        diaryRepository.recentEntries().takeIf { it.isNotEmpty() }?.let { formatDiaryContext(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

/** 把日记摘要拼成一条 system 消息，引导角色找合适时机自然地关心用户 */
internal fun ChatSession.formatDiaryContext(entries: List<DiaryEntry>): String = buildString {
    appendLine("以下是用户最近写下的心情日记，请在这次对话中找合适时机自然地关心一下（像真正在意对方一样，不要生硬复述原文，也不必每句都提）：")
    entries.forEach { entry ->
        val text = entry.summary.ifBlank { entry.content.replace('\n', ' ').trim().take(40) }
        appendLine("- ${entry.date}（心情${entry.mood}）：$text")
    }
}.trim()
