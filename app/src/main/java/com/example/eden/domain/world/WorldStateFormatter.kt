package com.example.eden.domain.world

import java.util.Calendar
import java.util.Locale

/**
 * 世界状态的 prompt 出口。纯字符串拼接，不依赖 Context，可直接单测。
 *
 * 只输出阶段名，不输出好感度数值：数值被模型复述会直接毁掉沉浸感。
 */
object WorldStateFormatter {

    fun toPrompt(s: WorldSnapshot): String = buildString {
        appendLine("【当前状态】")
        appendLine("时间：${weekdayName(s.dayOfWeek)} ${clock(s.hour, s.minute)}，${timeOfDayName(s.timeOfDay)}")
        appendLine("关系：${stageName(s.stage)}")
        appendLine("心情：${s.moodLabel}")
        appendLine("日程：${s.schedule.activity}（${busyName(s.schedule.busyLevel)}）")
        appendLine("环境：${environment(s)}")
        if (s.missing) {
            appendLine("距离上次聊天：${s.absenceDays} 天（她有点想念，但不会直接说）")
        }
        appendLine()
        appendLine("【行为约束】")
        constraints(s).forEach { appendLine("- $it") }
    }.trim()

    private fun constraints(s: WorldSnapshot): List<String> = buildList {
        when (s.timeOfDay) {
            TimeOfDay.LATE_NIGHT -> add("现在是深夜，回复要短，不要主动开启新话题")
            TimeOfDay.NIGHT -> add("现在是晚上，说话更轻、更短")
            TimeOfDay.MORNING -> add("现在是上午，她可能正在上课或通勤，回复从简")
            else -> Unit
        }
        when (s.stage) {
            RelationStage.STRANGER -> add("关系还很生疏，保持礼貌与距离，不要用亲昵称呼")
            RelationStage.ACQUAINTANCE -> add("关系是熟人，可以自然聊天，但不要用恋人级别的称呼")
            RelationStage.FRIEND -> add("关系是朋友，可以开玩笑，但不要用恋人级别的亲昵称呼")
            RelationStage.AMBIGUOUS, RelationStage.LOVER -> add("关系已经很亲近，可以更柔软，但保持三玖的含蓄")
        }
        when (s.schedule.busyLevel) {
            1 -> add("她正在${s.schedule.activity}，最多回两句")
            2 -> add("她正在${s.schedule.activity}，最多回一句，语气可以带一点匆忙")
            else -> Unit
        }
        if (s.mood.valence < -0.2f) add("情绪偏低落，不要用夸张语气，也不要主动撒娇")
        if (s.missing) add("你们几天没见了，她有一点想念，但不会直接说出来")
        s.weather?.let {
            if (it.text.contains("雨") || it.text.contains("雪")) add("外面${it.text}，可以自然带到天气")
        }
        add("以上状态只用于控制语气和内容取舍，不要在回复里提到状态、数值、系统或好感度")
    }

    private fun environment(s: WorldSnapshot): String {
        val head = s.festival ?: seasonName(s.season)
        val w = s.weather ?: return head
        val desc = if (w.text.contains("雨") || w.text.contains("雪")) "外面在下${w.text}" else "外面${w.text}"
        return "$head，$desc，${w.temp}℃"
    }

    private fun clock(hour: Int, minute: Int): String =
        String.format(Locale.US, "%02d:%02d", hour, minute)

    private fun weekdayName(dayOfWeek: Int): String = when (dayOfWeek) {
        Calendar.MONDAY -> "周一"
        Calendar.TUESDAY -> "周二"
        Calendar.WEDNESDAY -> "周三"
        Calendar.THURSDAY -> "周四"
        Calendar.FRIDAY -> "周五"
        Calendar.SATURDAY -> "周六"
        else -> "周日"
    }

    private fun timeOfDayName(t: TimeOfDay): String = when (t) {
        TimeOfDay.LATE_NIGHT -> "深夜"
        TimeOfDay.MORNING -> "上午"
        TimeOfDay.NOON -> "中午"
        TimeOfDay.AFTERNOON -> "下午"
        TimeOfDay.EVENING -> "傍晚"
        TimeOfDay.NIGHT -> "晚上"
    }

    private fun stageName(stage: RelationStage): String = when (stage) {
        RelationStage.STRANGER -> "陌生人"
        RelationStage.ACQUAINTANCE -> "熟人"
        RelationStage.FRIEND -> "朋友"
        RelationStage.AMBIGUOUS -> "暧昧"
        RelationStage.LOVER -> "恋人"
    }

    private fun busyName(busyLevel: Int): String = when (busyLevel) {
        0 -> "空闲"
        1 -> "有点忙"
        else -> "很忙"
    }

    private fun seasonName(season: Season): String = when (season) {
        Season.SPRING -> "春季"
        Season.SUMMER -> "夏季"
        Season.AUTUMN -> "秋季"
        Season.WINTER -> "冬季"
    }
}
