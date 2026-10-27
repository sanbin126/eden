package com.example.eden.domain.world

import android.content.Context
import com.example.eden.core.util.FestivalCalendar
import com.example.eden.domain.character.MikuPersona
import com.example.eden.domain.economy.EconomyManager
import com.example.eden.feature.story.StoryProgressRepository
import com.example.eden.feature.weather.WeatherRepository
import java.util.Calendar

/** 纯函数：好感区间 + 必要条件共同决定阶段。条件不满足时自动回落到更低阶段。 */
fun deriveStage(affection: Int, chapters: Set<Int>): RelationStage = when {
    affection >= 80 && 2 in chapters -> RelationStage.LOVER
    affection >= 55 && 2 in chapters -> RelationStage.AMBIGUOUS
    affection >= 30 && 1 in chapters -> RelationStage.FRIEND
    affection >= 10 -> RelationStage.ACQUAINTANCE
    else -> RelationStage.STRANGER
}

/** 只升不降：存档里的最高阶段高于当前派生阶段时，以存档为准。 */
fun effectiveStage(derived: RelationStage, storedHighest: RelationStage?): RelationStage =
    if (storedHighest != null && storedHighest.rank > derived.rank) storedHighest else derived

/** 情绪标签只用于展示，不参与计算。 */
fun moodLabel(mood: Mood, missing: Boolean): String = when {
    mood.arousal > 0.70f && mood.valence > 0.10f -> "有点慌，情绪起伏明显"
    mood.valence < -0.20f -> "低落"
    mood.valence > 0.30f && mood.arousal > 0.50f -> "开心"
    mood.valence > 0.30f -> "平静，心情不错"
    else -> "平静"
}

fun timeOfDay(hour: Int): TimeOfDay = when (hour) {
    in 0..4 -> TimeOfDay.LATE_NIGHT
    in 5..10 -> TimeOfDay.MORNING
    in 11..12 -> TimeOfDay.NOON
    in 13..16 -> TimeOfDay.AFTERNOON
    in 17..20 -> TimeOfDay.EVENING
    else -> TimeOfDay.NIGHT       // 21..23
}

fun seasonOf(month: Int): Season = when (month) {  // Calendar.MONTH 从 0 开始
    2, 3, 4 -> Season.SPRING
    5, 6, 7 -> Season.SUMMER
    8, 9, 10 -> Season.AUTUMN
    else -> Season.WINTER
}

object WorldState {
    /**
     * 组装只读快照：只读 prefs + 纯计算，不写盘、不发网络、不起协程。
     * 调用方需要自行控制频率（[com.example.eden.domain.model.ChatSession] 内有 1 秒缓存）。
     */
    fun snapshot(
        context: Context,
        characterId: String = MikuPersona.ID,
        now: Long = System.currentTimeMillis()
    ): WorldSnapshot {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val weekend = cal.get(Calendar.DAY_OF_WEEK).let {
            it == Calendar.SATURDAY || it == Calendar.SUNDAY
        }

        // 角色侧
        val prefs = WorldStateStore.prefs(context, characterId)
        val affection = EconomyManager.getAffection(context)
        val chapters = StoryProgressRepository.completedChapters(context, characterId)
        val derived = deriveStage(affection, chapters)
        val stage = effectiveStage(derived, WorldStateStore.readHighestStage(prefs, derived))
        val rawMood = WorldStateStore.readMood(prefs, now)          // 只读
        val mood = MoodMath.decayed(rawMood, stage, now)            // 只算，不写回
        val lastSeen = WorldStateStore.readLastSeen(context, characterId, now)
        val absenceDays = ((now - lastSeen).coerceAtLeast(0L) / DAY_MS).toInt()

        // 世界侧
        val festival = FestivalCalendar.todayFestival(cal)
        val weather = WeatherRepository.cached(context)?.let {
            WorldWeather(it.city, it.text, it.temp, it.tempChange)
        }

        return WorldSnapshot(
            now = now, hour = hour, minute = minute,
            dayOfWeek = cal.get(Calendar.DAY_OF_WEEK),
            timeOfDay = timeOfDay(hour), season = seasonOf(cal.get(Calendar.MONTH)),
            festival = festival, weather = weather,
            characterId = characterId, stage = stage, affection = affection,
            mood = mood, moodLabel = moodLabel(mood, absenceDays >= 2),
            schedule = CharacterSchedules.of(characterId).slotOf(hour, weekend, festival),
            absenceDays = absenceDays, missing = absenceDays >= 2
        )
    }
}
