package com.example.eden.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 聊天记录表实体。
 *
 * 相比原先「整个列表序列化成一段 JSON 塞进 SharedPreferences」的做法，
 * Room 只做增量写入（单条 insert），列表再长也不会每次全量序列化。
 *
 * @param type 存 [com.example.eden.domain.model.MessageType] 的枚举名（如 "TYPE_SENT"），
 *             用字符串而非枚举，避免以后增删枚举项导致数据库需要版本升级。
 */
@Entity(
    tableName = "chat_message",
    indices = [Index("characterId")]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 所属角色，便于将来支持多个角色各自独立的历史记录 */
    val characterId: String,
    val content: String,
    val type: String,
    val timestamp: Long,
    val isRead: Boolean = true
)
