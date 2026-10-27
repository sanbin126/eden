package com.example.eden.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** 专注任务 / 专注记录数据访问接口，方法均为挂起函数。 */
@Dao
interface FocusDao {

    // ==================== 任务 ====================

    /** 进行中的任务（未归档），按创建时间正序 */
    @Query("SELECT * FROM focus_task WHERE done = 0 ORDER BY createdAt ASC")
    suspend fun queryActiveTasks(): List<FocusTaskEntity>

    @Insert
    suspend fun insertTask(entity: FocusTaskEntity): Long

    /** 完成一段专注：给任务 +1 枚番茄 */
    @Query("UPDATE focus_task SET donePomodoros = donePomodoros + 1 WHERE id = :id")
    suspend fun incrementPomodoro(id: Long)

    /** 归档任务（从进行中列表移除，保留历史记录） */
    @Query("UPDATE focus_task SET done = 1 WHERE id = :id")
    suspend fun markTaskDone(id: Long)

    @Query("DELETE FROM focus_task")
    suspend fun deleteAllTasks()

    // ==================== 专注记录 ====================

    @Insert
    suspend fun insertSession(entity: FocusSessionEntity): Long

    /** 某天的专注记录，新的在前 */
    @Query("SELECT * FROM focus_session WHERE date = :date ORDER BY endedAt DESC")
    suspend fun querySessionsOfDate(date: String): List<FocusSessionEntity>

    /** 某天完成的番茄（专注段）数量 */
    @Query("SELECT COUNT(*) FROM focus_session WHERE date = :date")
    suspend fun countOfDate(date: String): Int

    /** 某天累计专注秒数 */
    @Query("SELECT COALESCE(SUM(durationSeconds), 0) FROM focus_session WHERE date = :date")
    suspend fun focusSecondsOfDate(date: String): Int

    /** 累计完成的番茄数量 */
    @Query("SELECT COUNT(*) FROM focus_session")
    suspend fun totalCount(): Int

    /** 累计专注秒数 */
    @Query("SELECT COALESCE(SUM(durationSeconds), 0) FROM focus_session")
    suspend fun totalFocusSeconds(): Int

    @Query("DELETE FROM focus_session")
    suspend fun deleteAllSessions()
}
