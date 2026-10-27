package com.example.eden.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** 结构化长期记忆的读写入口。所有查询都带 characterId，天然多角色隔离。 */
@Dao
interface LongTermMemoryDao {

    /** 某角色当前有效（未被取代）的全部记忆。 */
    @Query("SELECT * FROM long_term_memory WHERE characterId = :characterId AND supersededBy = 0")
    suspend fun queryActive(characterId: String): List<LongTermMemoryEntity>

    /** 管理页展示用：按重要度、最近提及时间排序。 */
    @Query(
        """
        SELECT * FROM long_term_memory
        WHERE characterId = :characterId AND supersededBy = 0
        ORDER BY importance DESC, lastMentionedAt DESC
        """
    )
    suspend fun queryActiveSorted(characterId: String): List<LongTermMemoryEntity>

    /** 按去重键查当前有效记忆，用于「说同一件事」时更新而不是新增。 */
    @Query(
        """
        SELECT * FROM long_term_memory
        WHERE characterId = :characterId AND `key` = :key AND supersededBy = 0
        ORDER BY lastMentionedAt DESC
        LIMIT 1
        """
    )
    suspend fun findActiveByKey(characterId: String, key: String): LongTermMemoryEntity?

    @Insert
    suspend fun insert(entity: LongTermMemoryEntity): Long

    /** 再次提到同一件事：提及次数 +1，刷新最近提及时间与来源。 */
    @Query(
        """
        UPDATE long_term_memory
        SET mentionCount = mentionCount + 1,
            lastMentionedAt = :now,
            sourceMessageId = :sourceMessageId
        WHERE id = :id
        """
    )
    suspend fun strengthen(id: Long, now: Long, sourceMessageId: Long)

    /** 冲突处理：旧记忆不删除，指向取代它的新记忆。 */
    @Query("UPDATE long_term_memory SET supersededBy = :newId WHERE id = :id")
    suspend fun markSuperseded(id: Long, newId: Long)

    @Query("DELETE FROM long_term_memory WHERE characterId = :characterId")
    suspend fun deleteByCharacter(characterId: String)

    /** 管理页手动遗忘单条记忆。 */
    @Query("DELETE FROM long_term_memory WHERE id = :id")
    suspend fun deleteById(id: Long)
}
