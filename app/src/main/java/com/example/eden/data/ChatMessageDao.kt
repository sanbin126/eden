package com.example.eden.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** 聊天记录数据访问接口，所有方法均为挂起函数，便于在协程中调用。 */
@Dao
interface ChatMessageDao {

    /** 按时间正序取出某个角色的全部聊天记录 */
    @Query("SELECT * FROM chat_message WHERE characterId = :characterId ORDER BY timestamp ASC, id ASC")
    suspend fun queryByCharacter(characterId: String): List<ChatMessageEntity>

    /** 单条追加，返回自增主键 */
    @Insert
    suspend fun insert(entity: ChatMessageEntity): Long

    /** 批量插入（用于从旧版 SharedPreferences 一次性导入历史数据） */
    @Insert
    suspend fun insertAll(entities: List<ChatMessageEntity>)

    /** 清空某个角色的聊天记录 */
    @Query("DELETE FROM chat_message WHERE characterId = :characterId")
    suspend fun deleteByCharacter(characterId: String)

    /** 按主键删除单条记录（用于长按菜单删除 / 重新生成时抹掉旧回复） */
    @Query("DELETE FROM chat_message WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 删除某角色遗留的日期分隔线（旧版本落库产生，现已改为渲染时动态生成），返回删除条数 */
    @Query("DELETE FROM chat_message WHERE characterId = :characterId AND type = 'TYPE_DIVIDER'")
    suspend fun deleteDividers(characterId: String): Int

    /** 某个角色已有的记录条数，用于判断是否需要做旧数据迁移 */
    @Query("SELECT COUNT(*) FROM chat_message WHERE characterId = :characterId")
    suspend fun countByCharacter(characterId: String): Int

    /**
     * 该角色最早一条消息的时间戳。用于"在一起第 X 天"的回退起点（用户未设纪念日时）。
     * 无记录返回 null。
     */
    @Query("SELECT timestamp FROM chat_message WHERE characterId = :characterId ORDER BY timestamp ASC, id ASC LIMIT 1")
    suspend fun firstMessageTimestamp(characterId: String): Long?

    /**
     * 取出尚未纳入长期记忆的对话原文（跳过日期分隔线等非对话消息）。
     * 基于数据库全量记录查询，而不是界面上被截断的消息列表，
     * 这样窗口外的内容才有机会被压缩进记忆。
     */
    @Query(
        "SELECT * FROM chat_message WHERE characterId = :characterId AND id > :afterId " +
            "AND type IN ('TYPE_SENT', 'TYPE_RECEIVED') ORDER BY timestamp ASC, id ASC"
    )
    suspend fun queryPendingMemory(characterId: String, afterId: Long): List<ChatMessageEntity>
}
