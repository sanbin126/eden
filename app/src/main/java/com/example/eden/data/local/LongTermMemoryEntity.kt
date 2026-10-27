package com.example.eden.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 结构化长期记忆表：一条事实一行。
 *
 * 与 [ChatMemoryEntity] 职责分开：
 * - [ChatMemoryEntity] 是「滚动摘要 + 事实 JSON」，每角色一行，服务于上下文压缩；
 * - 本表是「可检索的事实库」，带来源、权重、时间，服务于跨周跨月的记忆召回。
 *
 * @param characterId 多角色隔离
 * @param category 事实类型：identity / preference / event / relationship
 * @param subject 主体：user / miku / shared
 * @param key 结构化去重键：name / exam / fav_food ……
 * @param content 自然语言描述："用户叫小川"
 * @param sourceMessageId 来自哪条消息，保证可追溯
 * @param importance 初始重要度 1~5
 * @param mentionCount 被提及次数，反复提及即为强化
 * @param createdAt 首次记住的时间
 * @param lastMentionedAt 最后一次被提及 / 强化的时间
 * @param supersededBy 被哪条新记忆取代；0 表示仍有效。旧记忆不删除，保留历史
 */
@Entity(
    tableName = "long_term_memory",
    indices = [
        Index("characterId"),
        Index(value = ["characterId", "key"]),
        Index("sourceMessageId")
    ]
)
data class LongTermMemoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val characterId: String,
    val category: String,
    val subject: String,
    val key: String,
    val content: String,
    val sourceMessageId: Long,
    val importance: Int,
    val mentionCount: Int,
    val createdAt: Long,
    val lastMentionedAt: Long,
    val supersededBy: Long = 0
)
