package com.example.eden.core.util


import com.example.eden.feature.anniversary.AnniversaryNotifier
import java.util.Calendar

/**
 * 今天是哪个节日。同时支持公历节日与农历节日。
 *
 * 公历节日直接按 "月-日" 字符串匹配；农历节日经 [LunarConverter] 把今天的公历日期
 * 换算成农历后再按 "月-日" 匹配（闰月与正序月共用同一节日判定，例如闰正月不会
 * 重新触发春节）。
 *
 * 返回值是节日名（如"春节"/"圣诞"），由 [AnniversaryNotifier] 用作去重 key 和
 * [com.example.eden.domain.character.MikuPersona.FESTIVAL_LINES] 的查找 key。
 */
object FestivalCalendar {

    /** 公历节日：key = "月-日"，value = 节日名 */
    private val SOLAR = mapOf(
        "1-1" to "元旦",
        "2-14" to "情人节",
        "3-14" to "白色情人节",
        "10-31" to "万圣节",
        "12-24" to "平安夜",
        "12-25" to "圣诞",
        "12-31" to "跨年"
    )

    /** 农历节日：key = "月-日"（不含闰月标识，正序月与闰月共用判定） */
    private val LUNAR = mapOf(
        "1-1" to "春节",
        "1-15" to "元宵",
        "5-5" to "端午",
        "7-7" to "七夕",
        "8-15" to "中秋",
        "9-9" to "重阳"
    )

    /**
     * 返回今天对应的节日名；今天不是任何节日时返回 null。
     * 同一天若既是公历节日又是农历节日，公历优先（实际几乎不会撞车）。
     */
    fun todayFestival(calendar: Calendar): String? {
        val gKey = "${calendar.get(Calendar.MONTH) + 1}-${calendar.get(Calendar.DAY_OF_MONTH)}"
        SOLAR[gKey]?.let { return it }

        val lunar = LunarConverter.gregorianToLunar(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH)
        ) ?: return null
        val lKey = "${lunar.month}-${lunar.day}"
        return LUNAR[lKey]
    }
}
