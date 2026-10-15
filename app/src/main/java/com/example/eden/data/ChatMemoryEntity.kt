package com.example.eden.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 长期记忆表实体：每个角色只保留一行。
 *
 * 聊天原文是全量存在 [ChatMessageEntity] 里的，但发给模型的上下文长度有限，
 * 超出窗口的旧对话会彻底丢失。这张表保存「压缩后的长期记忆」，
 * 每次请求时随人设一起注入，解决「聊久了失忆」的问题。
 *
 * @param summary 滚动摘要：把窗口外的旧对话压缩成一段第三人称概述。
 * @param facts 结构化事实（JSON 数组字符串）：用户偏好、称呼、重要事件等。
 * @param summarizedUntilId 已纳入记忆的最大消息 id，用于只对新消息做增量压缩。
 */
@Entity(tableName = "chat_memory")
data class ChatMemoryEntity(
    @PrimaryKey
    val characterId: String,
    val summary: String,
    val facts: String,
    val summarizedUntilId: Long,
    val updatedAt: Long
)

/** 长期记忆的内存模型，供仓库层向上层返回。 */
data class ChatMemory(
    val summary: String = "",
    val facts: List<String> = emptyList(),
    /** 已纳入记忆的最大消息 id；0 表示尚未做过任何压缩 */
    val summarizedUntilId: Long = 0L
) {
    /** 是否为空记忆（空记忆不注入，避免浪费 token） */
    val isEmpty: Boolean get() = summary.isBlank() && facts.isEmpty()

    companion object {
        val EMPTY = ChatMemory()
    }
}
