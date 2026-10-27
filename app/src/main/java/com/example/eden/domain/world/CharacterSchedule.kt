package com.example.eden.domain.world

import com.example.eden.domain.character.MikuPersona

/** 角色日程的可替换实现：以后加二乃、五月只需新增一个 object，不动 snapshot。 */
interface CharacterSchedule {
    fun slotOf(hour: Int, weekend: Boolean, festival: String?): ScheduleSlot
}

object MikuSchedule : CharacterSchedule {
    private val WEEKDAY = listOf(
        ScheduleSlot(0, 6, "睡觉", 2),
        ScheduleSlot(6, 7, "起床、准备上学", 1),
        ScheduleSlot(7, 8, "通勤", 1),
        ScheduleSlot(8, 12, "上课", 2),
        ScheduleSlot(12, 13, "午休", 0),
        ScheduleSlot(13, 15, "上课", 2),
        ScheduleSlot(15, 17, "图书馆自习", 1),
        ScheduleSlot(17, 19, "晚饭", 0),
        ScheduleSlot(19, 21, "听历史讲座、看书", 0),
        ScheduleSlot(21, 22, "洗澡、收拾", 1),
        ScheduleSlot(22, 24, "睡前看书", 1)
    )

    private val WEEKEND = listOf(
        ScheduleSlot(0, 8, "睡觉", 2),
        ScheduleSlot(8, 10, "早餐", 0),
        ScheduleSlot(10, 12, "看书", 1),
        ScheduleSlot(12, 14, "午饭", 0),
        ScheduleSlot(14, 17, "去书店", 1),
        ScheduleSlot(17, 19, "晚饭", 0),
        ScheduleSlot(19, 23, "自由时间", 0),
        ScheduleSlot(23, 24, "准备睡觉", 1)
    )

    /** 优先级：节日（9:00–22:00）> 周末 > 工作日。 */
    override fun slotOf(hour: Int, weekend: Boolean, festival: String?): ScheduleSlot {
        if (festival != null && hour in 9 until 22) {
            return ScheduleSlot(9, 22, "过节：$festival", 0, weekdaysOnly = false)
        }
        val table = if (weekend) WEEKEND else WEEKDAY
        return table.firstOrNull { hour >= it.startHour && hour < it.endHour }
            ?: table.last()
    }
}

object CharacterSchedules {
    /** 本期只实现三玖；未注册的角色先共用三玖日程，不抛异常。 */
    fun of(characterId: String): CharacterSchedule = when (characterId) {
        MikuPersona.ID -> MikuSchedule
        else -> MikuSchedule
    }
}
