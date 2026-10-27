package com.example.eden.domain.world

import android.content.Context
import android.content.SharedPreferences
import com.example.eden.core.storage.EdenStorage
import com.example.eden.domain.character.MikuPersona
import com.example.eden.domain.economy.EconomyManager
import com.example.eden.feature.daily.DailyLoopManager
import com.example.eden.feature.story.StoryProgressRepository

/**
 * 世界状态的存储入口。
 *
 * 唯一写入口是 [applyEvent]，业务模块不允许直接改 `world_prefs_*`。
 * 快照（[WorldState.snapshot]）只调用本类的只读方法，不写盘。
 */
object WorldStateStore {
    const val KEY_STAGE = "stage"
    const val KEY_MOOD_V = "mood_v"
    const val KEY_MOOD_A = "mood_a"
    const val KEY_MOOD_AT = "mood_at"
    const val KEY_LAST_SEEN = "last_seen_at"

    fun prefs(context: Context, characterId: String = MikuPersona.ID): SharedPreferences =
        EdenStorage.prefs(context, "world_prefs_$characterId")

    /** 无存档时直接返回派生值；有存档时取 max。纯读，不写盘。 */
    fun readHighestStage(prefs: SharedPreferences, derived: RelationStage): RelationStage {
        val stored = prefs.getString(KEY_STAGE, null)?.let {
            runCatching { RelationStage.valueOf(it) }.getOrNull()
        }
        return if (stored == null || stored.rank < derived.rank) derived else stored
    }

    /** 读取上次应用事件时的原始值；无存档时返回 neutral，不写盘。 */
    fun readMood(prefs: SharedPreferences, now: Long): Mood {
        if (!prefs.contains(KEY_MOOD_AT)) return Mood.neutral(now)
        return Mood(
            valence = prefs.getFloat(KEY_MOOD_V, 0f).coerceIn(-1f, 1f),
            arousal = prefs.getFloat(KEY_MOOD_A, MoodMath.AROUSAL_BASELINE).coerceIn(0f, 1f),
            at = prefs.getLong(KEY_MOOD_AT, now)
        )
    }

    /** 上次互动时间；world 存档缺失时回退到退出时间，再缺失用 now（首次使用不产生「久别」）。 */
    fun readLastSeen(
        context: Context,
        characterId: String = MikuPersona.ID,
        now: Long = System.currentTimeMillis()
    ): Long {
        val stored = prefs(context, characterId).getLong(KEY_LAST_SEEN, 0L)
        if (stored > 0L) return stored
        val exitTime = DailyLoopManager.lastExitTime(context)
        return if (exitTime > 0L) exitTime else now
    }

    /**
     * 唯一的事件写入口。
     *
     * 顺序固定：先结算久别（仅 SessionActive）→ 再应用事件 → 最后刷新阶段与 last_seen。
     * `last_seen_at` 必须在久别结算之后更新，否则同一次调用里刚读到的旧值会被自己覆盖。
     */
    fun applyEvent(
        context: Context,
        characterId: String = MikuPersona.ID,
        event: WorldEvent,
        now: Long = System.currentTimeMillis()
    ) {
        val prefs = prefs(context, characterId)
        val derived = deriveStage(
            EconomyManager.getAffection(context),
            StoryProgressRepository.completedChapters(context, characterId)
        )
        val stage = effectiveStage(derived, readHighestStage(prefs, derived))

        // 1) 久别只在会话开始时结算，且每天最多一次
        if (event is WorldEvent.SessionActive) {
            absenceEvent(context, prefs, now)?.let { settle(prefs, it, stage, now) }
        }

        // 2) 正常事件：先衰减、再叠加、再落时间戳
        settle(prefs, event, stage, now)

        // 3) 阶段只升不降：派升值高于存档时才写
        val stored = readHighestStage(prefs, derived)
        if (derived.rank > stored.rank) {
            prefs.edit().putString(KEY_STAGE, derived.name).apply()
        }

        // 4) 会话活跃 / 用户发言刷新 last_seen
        if (event is WorldEvent.SessionActive || event is WorldEvent.UserMessage) {
            prefs.edit().putLong(KEY_LAST_SEEN, now).apply()
        }
    }

    private fun settle(
        prefs: SharedPreferences,
        event: WorldEvent,
        stage: RelationStage,
        now: Long
    ) {
        val next = MoodMath.apply(readMood(prefs, now), event, stage, now)
        prefs.edit()
            .putFloat(KEY_MOOD_V, next.valence)
            .putFloat(KEY_MOOD_A, next.arousal)
            .putLong(KEY_MOOD_AT, next.at)
            .apply()
    }

    /** 距上次互动 ≥ 3 天才结算，且每自然日最多一次，避免一天内反复冷启动连续扣情绪。 */
    private fun absenceEvent(context: Context, prefs: SharedPreferences, now: Long): WorldEvent? {
        val last = prefs.getLong(KEY_LAST_SEEN, 0L)
        if (last <= 0L) return null
        val days = ((now - last) / DAY_MS).toInt()
        if (days < 3) return null
        return if (EconomyManager.tryDailyGrant(context, "world_absent", 1) == 1) {
            WorldEvent.AbsenceSettled(days)
        } else null
    }
}
