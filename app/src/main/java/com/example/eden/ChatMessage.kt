package com.example.eden

import java.util.Date

enum class MessageType {
    TYPE_SENT,
    TYPE_RECEIVED,
    TYPE_DIVIDER      // 新增：分隔线
}

data class ChatMessage(
    val id: Long = 0,
    val content: String,
    val type: MessageType,
    val timestamp: Long = System.currentTimeMillis(),
    val isRead: Boolean = true
)