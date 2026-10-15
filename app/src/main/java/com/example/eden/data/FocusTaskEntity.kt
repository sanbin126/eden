package com.example.eden.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 专注任务表实体。
 *
 * 与「番茄 todo」的任务一致：一个任务对应若干枚番茄钟，每完成一段专注（FOCUS）
 * 就给对应任务 +1。任务完成后标记 done，不在「进行中」列表里展示。
 *
 * @param targetPomodoros 计划番茄数（仅用于展示进度，不做强制约束）
 * @param donePomodoros   已完成番茄数
 * @param done            true 表示该任务已归档（完成/删除后不再显示）
 */
@Entity(tableName = "focus_task")
data class FocusTaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val targetPomodoros: Int = 1,
    val donePomodoros: Int = 0,
    val done: Boolean = false,
    val createdAt: Long
)

/** 专注任务领域模型，供上层使用。 */
data class FocusTask(
    val id: Long,
    val title: String,
    val targetPomodoros: Int,
    val donePomodoros: Int,
    val createdAt: Long
) {
    /** 是否已达成计划番茄数 */
    val isTargetReached: Boolean
        get() = donePomodoros >= targetPomodoros
}

/**
 * 专注记录表实体：每次完整走完一段 FOCUS 就落一条，用于统计。
 *
 * @param taskId 关联任务 id，0 表示没有指定任务
 * @param date   "yyyy-MM-dd"，建索引用于按天统计
 */
@Entity(tableName = "focus_session", indices = [androidx.room.Index("date")])
data class FocusSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val taskId: Long,
    val taskTitle: String,
    val durationSeconds: Int,
    val date: String,
    val endedAt: Long
)
