package com.example.eden.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** 日记数据访问接口，所有方法均为挂起函数，便于在协程中调用。 */
@Dao
interface DiaryDao {

    /** 全部日记，按创建时间倒序（新的在前） */
    @Query("SELECT * FROM diary ORDER BY createdAt DESC")
    suspend fun queryAll(): List<DiaryEntity>

    /**
     * 某日期（含）之后的日记，新的在前。
     * date 为 "yyyy-MM-dd"，可直接按字典序比较来筛"今天/昨天"。
     */
    @Query("SELECT * FROM diary WHERE date >= :fromDate ORDER BY createdAt DESC")
    suspend fun querySince(fromDate: String): List<DiaryEntity>

    /** 现有条数，用于判断是否需要用旧版 SharedPreferences 数据做一次性迁移 */
    @Query("SELECT COUNT(*) FROM diary")
    suspend fun count(): Int

    /** 追加一条，返回自增主键 */
    @Insert
    suspend fun insert(entity: DiaryEntity): Long

    /** 批量插入（旧数据迁移 / 后端导入） */
    @Insert
    suspend fun insertAll(entities: List<DiaryEntity>)

    /** 回填 LLM 生成的摘要（保存后异步补齐，不阻塞保存流程） */
    @Query("UPDATE diary SET summary = :summary WHERE id = :id")
    suspend fun updateSummary(id: Long, summary: String)

    /** 清空全部日记（后端导入前先清空） */
    @Query("DELETE FROM diary")
    suspend fun deleteAll()
}
