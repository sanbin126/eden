package com.example.eden.domain.world

import kotlin.math.pow

object MoodMath {
    const val VALENCE_HALF_LIFE_MS = 3 * 60 * 60 * 1000L   // 3 小时
    const val AROUSAL_HALF_LIFE_MS = 60 * 60 * 1000L       // 1 小时
    const val AROUSAL_BASELINE = 0.30f

    fun decay(current: Float, baseline: Float, halfLifeMs: Long, elapsedMs: Long): Float {
        if (elapsedMs <= 0L) return current
        val factor = 0.5.pow(elapsedMs.toDouble() / halfLifeMs).toFloat()
        return baseline + (current - baseline) * factor
    }

    /** 先衰减到 now，再落新时间戳；避免同一次读取既衰减又重复衰减。 */
    fun decayed(mood: Mood, stage: RelationStage, now: Long): Mood = Mood(
        valence = decay(mood.valence, stage.moodBaseline(), VALENCE_HALF_LIFE_MS, now - mood.at)
            .coerceIn(-1f, 1f),
        arousal = decay(mood.arousal, AROUSAL_BASELINE, AROUSAL_HALF_LIFE_MS, now - mood.at)
            .coerceIn(0f, 1f),
        at = now
    )

    /** 唯一的事件应用口径：先衰减到 now，再叠加 delta。 */
    fun apply(mood: Mood, event: WorldEvent, stage: RelationStage, now: Long): Mood {
        val base = decayed(mood, stage, now)
        val (dv, da) = event.delta()
        return Mood(
            valence = (base.valence + dv).coerceIn(-1f, 1f),
            arousal = (base.arousal + da).coerceIn(0f, 1f),
            at = now
        )
    }
}
