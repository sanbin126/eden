package com.example.eden.domain.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import kotlin.math.abs

class WorldStateTest {

    private val t0 = 1_700_000_000_000L
    private val hourMs = 60 * 60 * 1000L

    // ==================== 1. 时段 ====================
    @Test
    fun `time of day boundaries`() {
        assertEquals(TimeOfDay.LATE_NIGHT, timeOfDay(4))
        assertEquals(TimeOfDay.MORNING, timeOfDay(5))
        assertEquals(TimeOfDay.NOON, timeOfDay(11))
        assertEquals(TimeOfDay.AFTERNOON, timeOfDay(13))
        assertEquals(TimeOfDay.EVENING, timeOfDay(17))
        assertEquals(TimeOfDay.NIGHT, timeOfDay(21))
    }

    // ==================== 2. 季节（Calendar 月份从 0 开始） ====================
    @Test
    fun `season of month`() {
        assertEquals(Season.WINTER, seasonOf(1))   // 2 月
        assertEquals(Season.SPRING, seasonOf(2))   // 3 月
        assertEquals(Season.SUMMER, seasonOf(5))
        assertEquals(Season.AUTUMN, seasonOf(8))
    }

    // ==================== 3-9. 关系阶段派生 ====================
    @Test
    fun `derive stage below acquaintance`() {
        assertEquals(RelationStage.STRANGER, deriveStage(9, emptySet()))
    }

    @Test
    fun `derive stage acquaintance threshold`() {
        assertEquals(RelationStage.ACQUAINTANCE, deriveStage(10, emptySet()))
    }

    @Test
    fun `derive stage friend requires chapter one`() {
        assertEquals(RelationStage.ACQUAINTANCE, deriveStage(30, emptySet()))
    }

    @Test
    fun `derive stage friend when chapter one done`() {
        assertEquals(RelationStage.FRIEND, deriveStage(30, setOf(1)))
    }

    @Test
    fun `derive stage ambiguous requires chapter two`() {
        assertEquals(RelationStage.FRIEND, deriveStage(65, setOf(1)))
    }

    @Test
    fun `derive stage ambiguous threshold`() {
        assertEquals(RelationStage.AMBIGUOUS, deriveStage(55, setOf(1, 2)))
    }

    @Test
    fun `derive stage lover threshold`() {
        assertEquals(RelationStage.LOVER, deriveStage(80, setOf(1, 2)))
    }

    // ==================== 10. 只升不降 ====================
    @Test
    fun `effective stage never goes down`() {
        assertEquals(RelationStage.LOVER, effectiveStage(RelationStage.FRIEND, RelationStage.LOVER))
        assertEquals(RelationStage.FRIEND, effectiveStage(RelationStage.FRIEND, null))
        assertEquals(RelationStage.AMBIGUOUS, effectiveStage(RelationStage.AMBIGUOUS, RelationStage.FRIEND))
    }

    // ==================== 11-14. 衰减 ====================
    @Test
    fun `decay one half life`() {
        val v = MoodMath.decay(0.8f, 0.3f, MoodMath.VALENCE_HALF_LIFE_MS, 3 * hourMs)
        assertEquals(0.55, v.toDouble(), 0.001)
    }

    @Test
    fun `decay two half lives`() {
        val v = MoodMath.decay(0.8f, 0.3f, MoodMath.VALENCE_HALF_LIFE_MS, 6 * hourMs)
        assertEquals(0.425, v.toDouble(), 0.001)
    }

    @Test
    fun `decay returns to baseline after long time`() {
        val v = MoodMath.decay(0.8f, 0.3f, MoodMath.VALENCE_HALF_LIFE_MS, 48 * hourMs)
        assertEquals(0.3, v.toDouble(), 0.001)
    }

    @Test
    fun `arousal decays in one hour`() {
        val v = MoodMath.decay(0.9f, MoodMath.AROUSAL_BASELINE, MoodMath.AROUSAL_HALF_LIFE_MS, hourMs)
        assertEquals(0.6, v.toDouble(), 0.001)
    }

    // ==================== 15-16. 叠加边界 ====================
    @Test
    fun `apply clamps upper bound`() {
        val mood = MoodMath.apply(
            Mood(0.9f, 0.5f, t0), WorldEvent.Praised, RelationStage.FRIEND, t0
        )
        assertEquals(1.0, mood.valence.toDouble(), 0.0001)
    }

    @Test
    fun `apply clamps lower bound`() {
        val mood = MoodMath.apply(
            Mood(-0.9f, 0.5f, t0), WorldEvent.AbsenceSettled(4), RelationStage.FRIEND, t0
        )
        assertEquals(-1.0, mood.valence.toDouble(), 0.0001)
    }

    // ==================== 17-20. 日程查表 ====================
    @Test
    fun `schedule weekday nine is class`() {
        val slot = MikuSchedule.slotOf(9, weekend = false, festival = null)
        assertEquals("上课", slot.activity)
        assertEquals(2, slot.busyLevel)
    }

    @Test
    fun `schedule weekend nine is breakfast`() {
        val slot = MikuSchedule.slotOf(9, weekend = true, festival = null)
        assertEquals("早餐", slot.activity)
        assertEquals(0, slot.busyLevel)
    }

    @Test
    fun `schedule festival evening is holiday`() {
        val slot = MikuSchedule.slotOf(20, weekend = false, festival = "春节")
        assertTrue(slot.activity.startsWith("过节："))
        assertEquals(0, slot.busyLevel)
    }

    @Test
    fun `schedule festival late night still sleeps`() {
        val slot = MikuSchedule.slotOf(2, weekend = false, festival = "春节")
        assertEquals("睡觉", slot.activity)
        assertEquals(2, slot.busyLevel)
    }

    // ==================== 21-23. Formatter ====================
    @Test
    fun `prompt contains status relation and constraints`() {
        val text = WorldStateFormatter.toPrompt(snapshot())
        assertTrue(text.contains("【当前状态】"))
        assertTrue(text.contains("【行为约束】"))
        assertTrue(text.contains("朋友"))
        assertTrue(text.contains("以上状态只用于控制语气和内容取舍，不要在回复里提到状态、数值、系统或好感度"))
    }

    @Test
    fun `prompt omits weather when cache missing`() {
        val text = WorldStateFormatter.toPrompt(snapshot(weather = null))
        assertFalse(text.contains("外面"))
    }

    @Test
    fun `prompt mentions missing when away for days`() {
        val text = WorldStateFormatter.toPrompt(snapshot(missing = true, absenceDays = 3))
        assertTrue(text.contains("几天没见"))
    }

    // ==================== 24. 情绪标签 ====================
    @Test
    fun `mood label ranges`() {
        assertEquals("有点慌，情绪起伏明显", moodLabel(Mood(0.2f, 0.8f, t0), false))
        assertEquals("低落", moodLabel(Mood(-0.3f, 0.4f, t0), false))
        assertEquals("开心", moodLabel(Mood(0.4f, 0.6f, t0), false))
        assertEquals("平静，心情不错", moodLabel(Mood(0.4f, 0.4f, t0), false))
        assertEquals("平静", moodLabel(Mood(0f, 0.3f, t0), false))
    }

    // ==================== 25-27. applyEvent 顺序 ====================
    @Test
    fun `apply decays first then adds delta`() {
        val now = t0 + 3 * hourMs
        val result = MoodMath.apply(
            Mood(0.8f, MoodMath.AROUSAL_BASELINE, t0),
            WorldEvent.Praised,
            RelationStage.FRIEND,
            now
        )
        val expected = MoodMath.decay(
            0.8f, RelationStage.FRIEND.moodBaseline(), MoodMath.VALENCE_HALF_LIFE_MS, now - t0
        ) + 0.30f
        assertEquals(expected.toDouble(), result.valence.toDouble(), 0.001)
    }

    @Test
    fun `adding before decaying gives a different result`() {
        val now = t0 + 3 * hourMs
        val decayFirst = MoodMath.apply(
            Mood(0.8f, MoodMath.AROUSAL_BASELINE, t0),
            WorldEvent.Praised,
            RelationStage.FRIEND,
            now
        ).valence

        // 错误口径：先在 t0 叠加（被 clamp 到 1.0），再整体衰减 3 小时
        val addFirst = MoodMath.decay(
            (0.8f + 0.30f).coerceAtMost(1f),
            RelationStage.FRIEND.moodBaseline(),
            MoodMath.VALENCE_HALF_LIFE_MS,
            now - t0
        )

        assertTrue(abs(decayFirst - addFirst) > 0.01f)
    }

    @Test
    fun `session active keeps baseline and advances timestamp`() {
        val baseline = RelationStage.FRIEND.moodBaseline()
        val first = MoodMath.apply(
            Mood(baseline, MoodMath.AROUSAL_BASELINE, t0),
            WorldEvent.SessionActive,
            RelationStage.FRIEND,
            t0 + hourMs
        )
        val second = MoodMath.apply(
            first,
            WorldEvent.SessionActive,
            RelationStage.FRIEND,
            t0 + 2 * hourMs
        )
        assertTrue(abs(second.valence - baseline) < 0.001f)
        assertEquals(t0 + 2 * hourMs, second.at)
    }

    private fun snapshot(
        stage: RelationStage = RelationStage.FRIEND,
        weather: WorldWeather? = WorldWeather("东京", "小雨", 18, null),
        missing: Boolean = false,
        absenceDays: Int = 0
    ) = WorldSnapshot(
        now = t0,
        hour = 22,
        minute = 30,
        dayOfWeek = Calendar.WEDNESDAY,
        timeOfDay = TimeOfDay.NIGHT,
        season = Season.AUTUMN,
        festival = null,
        weather = weather,
        characterId = "miku",
        stage = stage,
        affection = 42,
        mood = Mood(0.1f, 0.3f, t0),
        moodLabel = "平静",
        schedule = ScheduleSlot(22, 24, "睡前看书", 1),
        absenceDays = absenceDays,
        missing = missing
    )
}
