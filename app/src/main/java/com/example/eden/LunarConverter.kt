package com.example.eden

/**
 * 公历 → 农历转换器（1900-2099）。
 *
 * 使用经典 LunarInfo 200 年查找表算法（与开源 lunar-javascript / chinese-lunar 等库一致），
 * 无三方依赖。算法要点：
 * - LunarInfo[i] 编码 i+1900 年的农历年信息：
 *   - 低 4 位（& 0xf）：闰月序号（0 = 无闰月，1-12 = 闰某月）
 *   - 第 16 位（& 0x10000）：闰月天数（1 = 30，0 = 29），仅在存在闰月时有效
 *   - 第 4-15 位：1-12 月的大小月标记（1 = 30 天，0 = 29 天）
 * - 基准日：1900-01-31（公历）= 农历 1900 年正月初一
 * - 通过 Julian Day Number 差值累计天数，避开 java.util.Calendar 的时区 / DST 问题
 *
 * 仅用于节日检测场景，对性能没有要求，可读性优先。
 */
object LunarConverter {

    data class LunarDate(
        val year: Int,    // 农历年（如 2023）
        val month: Int,   // 农历月 1-12
        val day: Int,     // 农历日 1-30
        val isLeapMonth: Boolean
    )

    private val LUNAR_INFO = intArrayOf(
        0x04bd8, 0x04ae0, 0x0a570, 0x054d0, 0x0d260, 0x0d950, 0x16554, 0x056a0, 0x09ad0, 0x055d2, // 1900-1909
        0x04ae0, 0x0a5b6, 0x0a4d0, 0x0d250, 0x1d255, 0x0b540, 0x0d6a0, 0x0ada2, 0x095b0, 0x14977, // 1910-1919
        0x04970, 0x0a4b0, 0x0b4b5, 0x06a50, 0x06d40, 0x0ab54, 0x02b60, 0x09570, 0x052f2, 0x04970, // 1920-1929
        0x06566, 0x0d4a0, 0x0ea50, 0x06e95, 0x05ad0, 0x02b60, 0x186e6, 0x092e0, 0x1c8d7, 0x0c950, // 1930-1939
        0x0d4a0, 0x1d8a6, 0x0b550, 0x056a0, 0x1a5b4, 0x025d0, 0x092d0, 0x0d2b2, 0x0a950, 0x0b557, // 1940-1949
        0x06ca0, 0x0b550, 0x15355, 0x04da0, 0x0a5b0, 0x14573, 0x052b0, 0x0a9a8, 0x0e950, 0x06aa0, // 1950-1959
        0x0aea6, 0x0ab50, 0x04b60, 0x0aae4, 0x0a570, 0x05260, 0x0f263, 0x0d950, 0x05b57, 0x056a0, // 1960-1969
        0x096d0, 0x04dd5, 0x04ad0, 0x0a4d0, 0x0d4d4, 0x0d250, 0x0d558, 0x0b540, 0x0b6a0, 0x195a6, // 1970-1979
        0x095b0, 0x049b0, 0x0a974, 0x0a4b0, 0x0b27a, 0x06a50, 0x06d40, 0x0af46, 0x0ab60, 0x09570, // 1980-1989
        0x04af5, 0x04970, 0x064b0, 0x074a3, 0x0ea50, 0x06b58, 0x055c0, 0x0ab60, 0x096d5, 0x092e0, // 1990-1999
        0x0c960, 0x0d954, 0x0d4a0, 0x0da50, 0x07552, 0x056a0, 0x0abb7, 0x025d0, 0x092d0, 0x0cab5, // 2000-2009
        0x0a950, 0x0b4a0, 0x0baa4, 0x0ad50, 0x055d9, 0x04ba0, 0x0a5b0, 0x15176, 0x052b0, 0x0a930, // 2010-2019
        0x07954, 0x06aa0, 0x0ad50, 0x05b52, 0x04b60, 0x0a6e6, 0x0a4e0, 0x0d260, 0x0ea65, 0x0d530, // 2020-2029
        0x05aa0, 0x076a3, 0x096d0, 0x04afb, 0x04ad0, 0x0a4d0, 0x1d0b6, 0x09570, 0x062b0, 0x0a970, // 2030-2039
        0x06554, 0x0d500, 0x0b540, 0x0b6a6, 0x196a0, 0x095b6, 0x049b0, 0x0a4b0, 0x0b275, 0x06a50, // 2040-2049
        0x06d40, 0x0af46, 0x0ab60, 0x09570, 0x04af5, 0x04970, 0x064b0, 0x074a3, 0x0ea50, 0x06b58, // 2050-2059
        0x055c0, 0x0ab60, 0x096d5, 0x092e0, 0x0c960, 0x0d954, 0x0d4a0, 0x0da50, 0x07552, 0x056a0, // 2060-2069
        0x0abb7, 0x025d0, 0x092d0, 0x0cab5, 0x0a950, 0x0b4a0, 0x0baa4, 0x0ad50, 0x055d9, 0x04ba0, // 2070-2079
        0x0a5b0, 0x15176, 0x052b0, 0x0a930, 0x07954, 0x06aa0, 0x0ad50, 0x05b52, 0x04b60, 0x0a6e6, // 2080-2089
        0x0a4e0, 0x0d260, 0x0ea65, 0x0d530, 0x05aa0, 0x076a3, 0x096d0, 0x04afb, 0x04ad0, 0x0a4d0  // 2090-2099
    )

    private const val BASE_YEAR = 1900
    private const val BASE_MONTH = 1
    private const val BASE_DAY = 31

    /**
     * 公历 → 农历。范围 1900-01-31 至 2099-12-31；越界或返回农历 1900 之前的日期返回 null。
     */
    fun gregorianToLunar(gYear: Int, gMonth: Int, gDay: Int): LunarDate? {
        if (gYear < BASE_YEAR || gYear > 2099) return null
        val baseJdn = julianDayNumber(BASE_YEAR, BASE_MONTH, BASE_DAY)
        val targetJdn = julianDayNumber(gYear, gMonth, gDay)
        var offset = targetJdn - baseJdn
        if (offset < 0) return null

        // 找到农历年：从 1900 起累减该年总天数
        var lunarYear = BASE_YEAR
        while (lunarYear < 2100) {
            val yd = lYearDays(lunarYear)
            if (offset < yd) break
            offset -= yd
            lunarYear++
        }
        if (lunarYear >= 2100) return null

        // 在该农历年内逐月扣减，注意闰月紧随同名正序月之后
        val leap = leapMonth(lunarYear) // 0 或 1-12
        var lunarMonth = 1
        while (lunarMonth <= 12) {
            val regDays = monthDays(lunarYear, lunarMonth)
            if (offset < regDays) {
                return LunarDate(lunarYear, lunarMonth, offset + 1, false)
            }
            offset -= regDays

            // 闰月出现在 lunarMonth == leap 之后、lunarMonth+1 之前
            if (leap != 0 && lunarMonth == leap) {
                val lpDays = leapDays(lunarYear)
                if (offset < lpDays) {
                    return LunarDate(lunarYear, lunarMonth, offset + 1, true)
                }
                offset -= lpDays
            }
            lunarMonth++
        }
        return null
    }

    /** 该农历年总天数（含闰月） */
    private fun lYearDays(y: Int): Int {
        var sum = 348 // 12 × 29 = 348（所有月都是小月的基础值）
        var bit = 0x8000
        while (bit > 0x8) {
            sum += if (info(y) and bit != 0) 1 else 0
            bit = bit shr 1
        }
        return sum + leapDays(y)
    }

    /** 闰月的天数（30 或 29）；无闰月返回 0 */
    private fun leapDays(y: Int): Int {
        if (leapMonth(y) == 0) return 0
        return if (info(y) and 0x10000 != 0) 30 else 29
    }

    /** 闰月序号（0 表示无闰月，1-12 表示闰某月） */
    private fun leapMonth(y: Int): Int = info(y) and 0xf

    /** 该农历年某月的天数（不含闰月，闰月天数另算） */
    private fun monthDays(y: Int, m: Int): Int =
        if (info(y) and (0x10000 shr m) != 0) 30 else 29

    private fun info(y: Int): Int = LUNAR_INFO[y - BASE_YEAR]

    /**
     * Julian Day Number（整数天，不含小时分秒）。
     * 标准天文公式，时区无关；与 java.util.Calendar 不同，不会因 DST 漂移一天。
     */
    private fun julianDayNumber(y: Int, m: Int, d: Int): Int {
        val a = (14 - m) / 12
        val yy = y + 4800 - a
        val mm = m + 12 * a - 3
        return d + (153 * mm + 2) / 5 + 365 * yy + yy / 4 - yy / 100 + yy / 400 - 32045
    }
}
