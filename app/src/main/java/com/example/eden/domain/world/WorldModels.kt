package com.example.eden.domain.world

/** 一天的毫秒数，用于「久别」等按整天计算的派生值。 */
const val DAY_MS = 24 * 60 * 60 * 1000L

/** 世界侧共享 + 角色侧隔离的统一快照。所有字段都是读时算出来的。 */
data class WorldSnapshot(
    val now: Long,
    val hour: Int,
    val minute: Int,
    val dayOfWeek: Int,          // Calendar.MONDAY..SUNDAY
    val timeOfDay: TimeOfDay,
    val season: Season,
    val festival: String?,       // null 表示不是节日
    val weather: WorldWeather?,  // null 表示没有可用缓存
    // ---- 角色侧（按 characterId 隔离）----
    val characterId: String,
    val stage: RelationStage,
    val affection: Int,
    val mood: Mood,
    val moodLabel: String,       // 只用于 prompt / UI 展示
    val schedule: ScheduleSlot,
    val absenceDays: Int,        // 距上次互动整天数
    val missing: Boolean         // absenceDays >= 2
)

data class WorldWeather(
    val city: String?,
    val text: String,
    val temp: Int,
    val tempChange: Int?
)

enum class Season { SPRING, SUMMER, AUTUMN, WINTER }

enum class TimeOfDay { LATE_NIGHT, MORNING, NOON, AFTERNOON, EVENING, NIGHT }

data class Mood(
    val valence: Float,   // -1f..1f
    val arousal: Float,   //  0f..1f
    val at: Long          // 该值的采样时刻
) {
    companion object {
        fun neutral(at: Long) = Mood(0f, MoodMath.AROUSAL_BASELINE, at)
    }
}

data class ScheduleSlot(
    val startHour: Int,
    val endHour: Int,
    val activity: String,
    val busyLevel: Int,               // 0 空闲 / 1 一般 / 2 很忙
    val weekdaysOnly: Boolean = true
)

enum class RelationStage(val rank: Int) {
    STRANGER(0), ACQUAINTANCE(1), FRIEND(2), AMBIGUOUS(3), LOVER(4);

    /** 情绪回归的基线：关系越近，安全感越高，坏情绪恢复得越快。 */
    fun moodBaseline(): Float = when (this) {
        STRANGER -> 0.10f
        ACQUAINTANCE -> 0.20f
        FRIEND -> 0.30f
        AMBIGUOUS -> 0.35f
        LOVER -> 0.40f
    }
}
