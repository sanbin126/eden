package com.example.eden.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 专注统计快照。 */
data class FocusStats(
    val todayCount: Int,
    val todaySeconds: Int,
    val totalCount: Int,
    val totalSeconds: Int
)

/**
 * 专注数据仓库：屏蔽 Room 细节，向上层提供「任务 / 记录 / 统计」三类操作。
 *
 * 只负责本地落库与查询，不含任何计时逻辑（计时在 [com.example.eden.FocusTimerManager]）。
 */
class FocusRepository(context: Context) {

    private val appContext = context.applicationContext

    // DAO 懒取：本地库按账号分区，切号后指向新账号的库，不能构造期缓存。
    private val dao get() = EdenDatabase.getInstance(appContext).focusDao()

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    // ==================== 任务 ====================

    /** 进行中的任务列表 */
    suspend fun activeTasks(): List<FocusTask> = withContext(Dispatchers.IO) {
        dao.queryActiveTasks().map {
            FocusTask(
                id = it.id,
                title = it.title,
                targetPomodoros = it.targetPomodoros,
                donePomodoros = it.donePomodoros,
                createdAt = it.createdAt
            )
        }
    }

    /** 新建任务，返回自增主键 */
    suspend fun addTask(title: String, targetPomodoros: Int): Long = withContext(Dispatchers.IO) {
        dao.insertTask(
            FocusTaskEntity(
                title = title.trim(),
                targetPomodoros = targetPomodoros.coerceAtLeast(1),
                createdAt = System.currentTimeMillis()
            )
        )
    }

    /** 归档任务（从进行中列表移除） */
    suspend fun archiveTask(id: Long) = withContext(Dispatchers.IO) {
        dao.markTaskDone(id)
    }

    // ==================== 记录 ====================

    /**
     * 记录一段完成的专注：写入记录并给任务 +1 枚番茄。
     *
     * @param taskId 0 表示没有关联任务，此时只记录不累加。
     */
    suspend fun completeFocus(
        taskId: Long,
        taskTitle: String,
        durationSeconds: Int
    ) = withContext(Dispatchers.IO) {
        dao.insertSession(
            FocusSessionEntity(
                taskId = taskId,
                taskTitle = taskTitle,
                durationSeconds = durationSeconds,
                date = today(),
                endedAt = System.currentTimeMillis()
            )
        )
        if (taskId > 0) dao.incrementPomodoro(taskId)
    }

    // ==================== 统计 ====================

    suspend fun stats(): FocusStats = withContext(Dispatchers.IO) {
        val date = today()
        FocusStats(
            todayCount = dao.countOfDate(date),
            todaySeconds = dao.focusSecondsOfDate(date),
            totalCount = dao.totalCount(),
            totalSeconds = dao.totalFocusSeconds()
        )
    }
}
