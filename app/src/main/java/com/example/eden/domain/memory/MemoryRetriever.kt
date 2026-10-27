package com.example.eden.domain.memory

import com.example.eden.data.local.LongTermMemoryEntity
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/**
 * 检索层：每次对话前挑出「此刻最相关」的记忆。
 *
 * - identity 类硬注入，永远在场；
 * - 其余按 相关度 / 重要度 / 新鲜度 / 提及频次 加权打分，取 Top-K；
 * - 遗忘分过低的记忆不删除，只是不参与软检索；
 * - 显式召回（「你还记得吗」）时跳过阈值，全量捞取。
 */
object MemoryRetriever {

    /** 时间衰减系数：约 35 天衰减到 0.5。 */
    private const val LAMBDA = 0.02

    /** 遗忘分低于此值的记忆不参与软检索。 */
    private const val MIN_DECAY_SCORE = 0.5

    private const val TOP_K = 6
    private const val RECALL_LIMIT = 12
    private const val HARD_LIMIT = 10

    private const val DAY_MS = 24 * 60 * 60 * 1000.0

    /** 常见虚词 2-gram，不参与相关度计算，避免「今天」「现在」误命中。 */
    private val STOP_BIGRAMS = setOf(
        "今天", "现在", "什么", "怎么", "可以", "我们", "你们", "他们", "就是", "还是",
        "这个", "那个", "一个", "不是", "没有", "知道", "觉得", "有点", "真的", "已经",
        "自己", "时候", "最近", "然后", "因为", "所以", "但是", "如果", "其实"
    )

    /**
     * @param query 当前输入 + 最近几轮对话，用于算相关度
     * @param forceAll 显式召回时为 true，忽略遗忘阈值
     */
    fun select(
        memories: List<LongTermMemoryEntity>,
        query: String,
        now: Long,
        forceAll: Boolean
    ): List<LongTermMemoryEntity> {
        if (memories.isEmpty()) return emptyList()

        val hard = memories
            .filter { it.category == MemoryCategory.IDENTITY.value }
            .sortedWith(compareByDescending<LongTermMemoryEntity> { it.importance }
                .thenByDescending { it.lastMentionedAt })
            .take(HARD_LIMIT)

        val queryTokens = bigrams(query)
        val soft = memories
            .asSequence()
            .filter { it.category != MemoryCategory.IDENTITY.value }
            .filter { forceAll || decayScore(it, now) >= MIN_DECAY_SCORE }
            .map { memory ->
                val relevance = relevance(memory, query, queryTokens)
                Triple(memory, relevance, score(memory, relevance, now))
            }
            // 喜好类只在话题相关时注入；重要事件（importance >= 4）允许主动想起
            .filter { forceAll || it.second > 0f || it.first.importance >= 4 }
            .sortedByDescending { it.third }
            .take(if (forceAll) RECALL_LIMIT else TOP_K)
            .map { it.first }
            .toList()

        return hard + soft
    }

    /**
     * 遗忘曲线：重要度 × 频次强化 × 时间衰减。
     * 久不提的分数自然下降；反复提到的被强化。
     */
    fun decayScore(memory: LongTermMemoryEntity, now: Long): Double {
        val days = daysSince(memory.lastMentionedAt, now)
        return memory.importance * (1 + ln(1.0 + memory.mentionCount)) * exp(-LAMBDA * days)
    }

    /** 综合得分：相关度 0.5 + 重要度 0.2 + 新鲜度 0.2 + 频次 0.1。 */
    private fun score(
        memory: LongTermMemoryEntity,
        relevance: Float,
        now: Long
    ): Float {
        val importance = (memory.importance / 5f).coerceIn(0f, 1f)
        val freshness = exp(-LAMBDA * daysSince(memory.lastMentionedAt, now)).toFloat()
        val mention = (ln(1.0 + memory.mentionCount) / ln(11.0)).coerceIn(0.0, 1.0).toFloat()
        return relevance * 0.5f + importance * 0.2f + freshness * 0.2f + mention * 0.1f
    }

    /** 关键词相关度：2-gram 交集比例；query 直接包含主题词时记为满分。 */
    private fun relevance(
        memory: LongTermMemoryEntity,
        query: String,
        queryTokens: Set<String>
    ): Float {
        topicOf(memory.key)?.let { topic ->
            if (query.contains(topic)) return 1f
        }
        if (queryTokens.isEmpty()) return 0f
        val contentTokens = bigrams(memory.content)
        if (contentTokens.isEmpty()) return 0f
        val matched = queryTokens.count { it in contentTokens }
        return (matched.toFloat() / min(queryTokens.size, contentTokens.size)).coerceIn(0f, 1f)
    }

    /** `fav_抹茶` → `抹茶`；`name` / `age` 这类固定键没有主题词。 */
    private fun topicOf(key: String): String? = when {
        key.startsWith("fav_") -> key.removePrefix("fav_").takeIf { it.isNotBlank() }
        key.startsWith("dislike_") -> key.removePrefix("dislike_").takeIf { it.isNotBlank() }
        else -> null
    }

    private fun daysSince(timestamp: Long, now: Long): Double =
        ((now - timestamp).coerceAtLeast(0L)) / DAY_MS

    /** 去掉标点与空白后切 2-gram，中文不需要分词也能work。 */
    private fun bigrams(text: String): Set<String> {
        val clean = text.filter { it.isLetterOrDigit() }
        if (clean.length < 2) return if (clean.isEmpty()) emptySet() else setOf(clean)
        val result = HashSet<String>(clean.length)
        for (i in 0 until clean.length - 1) {
            val gram = clean.substring(i, i + 2)
            if (gram !in STOP_BIGRAMS) result.add(gram)
        }
        return result
    }
}
