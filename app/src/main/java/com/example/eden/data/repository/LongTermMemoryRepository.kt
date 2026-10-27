package com.example.eden.data.repository

import android.content.Context
import com.example.eden.data.local.EdenDatabase
import com.example.eden.data.local.LongTermMemoryEntity
import com.example.eden.domain.memory.ExtractedFact
import com.example.eden.domain.memory.MemoryRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 结构化长期记忆仓库：负责去重、强化、冲突取代与检索。
 *
 * DAO 按需获取：本地库按账号分区，切号后 [EdenDatabase.getInstance] 会指向新库，
 * 不能在构造期缓存，否则会一直读写上一个账号的分区。
 */
class LongTermMemoryRepository(context: Context) {

    private val appContext = context.applicationContext

    private val dao get() = EdenDatabase.getInstance(appContext).longTermMemoryDao()

    /**
     * 落库一批抽取结果。
     *
     * - 同 `key` 且内容相同 → 强化（提及次数 +1）
     * - 同 `key` 但内容不同 → 新插入一条，并把旧记忆标记为被取代
     *
     * 旧记忆不删除，保留「你之前不是住重庆吗」这类回溯能力。
     */
    suspend fun remember(
        characterId: String,
        facts: List<ExtractedFact>,
        sourceMessageId: Long
    ) = withContext(Dispatchers.IO) {
        if (facts.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        facts.forEach { fact ->
            val existing = dao.findActiveByKey(characterId, fact.key)
            if (existing == null) {
                dao.insert(fact.toEntity(characterId, sourceMessageId, now))
            } else if (existing.content == fact.content) {
                dao.strengthen(existing.id, now, sourceMessageId)
            } else {
                val newId = dao.insert(fact.toEntity(characterId, sourceMessageId, now))
                dao.markSuperseded(existing.id, newId)
            }
        }
    }

    /** 按当前话题检索记忆；[forceAll] 为 true 时走显式召回（跳过遗忘阈值）。 */
    suspend fun retrieve(
        characterId: String,
        query: String,
        forceAll: Boolean = false
    ): List<LongTermMemoryEntity> = withContext(Dispatchers.IO) {
        val active = dao.queryActive(characterId)
        MemoryRetriever.select(active, query, System.currentTimeMillis(), forceAll)
    }

    suspend fun clear(characterId: String) = withContext(Dispatchers.IO) {
        dao.deleteByCharacter(characterId)
    }

    /** 管理页：列出当前有效的全部记忆（按重要度、最近提及排序）。 */
    suspend fun all(characterId: String): List<LongTermMemoryEntity> = withContext(Dispatchers.IO) {
        dao.queryActiveSorted(characterId)
    }

    /** 管理页：手动遗忘一条记忆。 */
    suspend fun forget(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteById(id)
    }

    private fun ExtractedFact.toEntity(
        characterId: String,
        sourceMessageId: Long,
        now: Long
    ) = LongTermMemoryEntity(
        characterId = characterId,
        category = category.value,
        subject = subject,
        key = key,
        content = content,
        sourceMessageId = sourceMessageId,
        importance = importance,
        mentionCount = 1,
        createdAt = now,
        lastMentionedAt = now,
        supersededBy = 0
    )
}
