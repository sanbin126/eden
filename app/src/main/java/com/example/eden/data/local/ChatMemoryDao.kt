package com.example.eden.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** 长期记忆数据访问接口。 */
@Dao
interface ChatMemoryDao {

    /** 读取某个角色的长期记忆，没有则返回 null */
    @Query("SELECT * FROM chat_memory WHERE characterId = :characterId")
    suspend fun query(characterId: String): ChatMemoryEntity?

    /** 写入/覆盖某个角色的长期记忆 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ChatMemoryEntity)

    /** 清空某个角色的长期记忆（与清空聊天记录同步） */
    @Query("DELETE FROM chat_memory WHERE characterId = :characterId")
    suspend fun deleteByCharacter(characterId: String)
}
