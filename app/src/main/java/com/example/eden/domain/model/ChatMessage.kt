package com.example.eden.domain.model

import java.util.Date

enum class MessageType {
    TYPE_SENT,
    TYPE_RECEIVED,
    TYPE_DIVIDER,     // 日期分隔线
    TYPE_FEED,        // 投喂记录分隔线（「投喂了 xxx」）
    TYPE_STORY_END,   // 小剧场结束横线
    TYPE_ERROR        // 回复失败提示（仅内存展示，不落库）
}

data class ChatMessage(
    val id: Long = 0,
    val content: String,
    val type: MessageType,
    val timestamp: Long = System.currentTimeMillis(),
    val isRead: Boolean = true
)
