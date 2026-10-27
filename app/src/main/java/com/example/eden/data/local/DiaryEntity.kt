package com.example.eden.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 日记表实体。
 *
 * 由原先「整个列表序列化成一整段 JSON 塞进 SharedPreferences、每次保存全量重写」
 * 改为 Room 单条写入：聊天时只需按日期增量读取今天/昨天的日记，不必加载全部。
 *
 * @param date 形如 "2026-09-30"，建索引用于按日期范围查询（yyyy-MM-dd 可直接按字典序比较）
 * @param tags 用户多选标签，存 JSON 数组字符串，避免为一个 List 引入 TypeConverter
 * @param summary 由 LLM 压缩成的一句话摘要（失败时为本地兜底），供聊天时注入使用
 */
@Entity(tableName = "diary", indices = [Index("date")])
data class DiaryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val date: String,
    val mood: String,
    val moodEmoji: String,
    val tags: String,
    val content: String,
    val summary: String,
    val createdAt: Long
)

/**
 * 日记领域模型，供上层（Activity / ViewModel）使用，隐藏 Room 实体细节。
 *
 * @param summary 一句话摘要；可能为空（未压缩成功时），聊天注入时回退到正文截断
 */
data class DiaryEntry(
    val id: Long = 0,
    val date: String,
    val mood: String,
    val moodEmoji: String,
    val tags: List<String>,
    val content: String,
    val summary: String = "",
    val createdAt: Long
) {
    /** 把 "2026-10-12" 显示为 "10月12日"；解析失败时原样返回 */
    val dateText: String
        get() = runCatching {
            val parts = date.split("-")
            "${parts[1].trimStart('0')}月${parts[2].trimStart('0')}日"
        }.getOrDefault(date)
}
