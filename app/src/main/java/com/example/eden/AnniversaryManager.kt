package com.example.eden

import android.content.Context
import com.example.eden.auth.SyncKeys
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 纪念日系统的本地状态存储。
 *
 * 三件事：(1) "在一起第 X 天"的起点日期（默认空，回退到首次聊天日期）；
 * (2) 用户生日（公历，按月日判定，不取年龄）；(3) 当日各类推送的去重标记，
 * 镜像 [GreetingManager] 的"日期级 key"模式——同一天同 kind 只推一次。
 *
 * 存储用 SharedPreferences，与 [SignInManager] / [GreetingManager] 同款风格，
 * 不需要 Room / Hilt 注入，调用方直接 get / set 即可。
 */
object AnniversaryManager {
    private const val PREFS_NAME = "anniversary_prefs"
    private const val KEY_START_DATE = "anniversary_start_date"   // "yyyy-MM-dd"
    private const val KEY_USER_BIRTHDAY = "user_birthday"          // "yyyy-MM-dd"
    private const val KEY_PUSH_PREFIX = "last_push_date_"          // + kind

    /**
     * milestone 天数集合。落在这些天数上即触发纪念日推送。
     * 1 = 认识第一天；100/200/365/500/730/1000 = 整百 / 整年里程碑；
     * 1500/2000/3000/5000 = 长期陪伴里程碑。任何 1000 的倍数也视为里程碑，
     * 以防用户陪伴超长。
     */
    val MILESTONES: Set<Int> = setOf(1, 100, 200, 365, 500, 730, 1000, 1500, 2000, 3000, 5000)

    private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    /** 纪念日起点日期串；未设置返回 null（调用方回退到首次聊天日期） */
    fun getStartDate(context: Context): String? =
        prefs(context).getString(KEY_START_DATE, null)?.takeIf { it.isNotBlank() }

    fun setStartDate(context: Context, dateStr: String) {
        prefs(context).edit().putString(KEY_START_DATE, dateStr).apply()
        SyncManager.notifyChanged(context, SyncKeys.ANNIVERSARY)
    }

    /** 用户生日串；未设置返回 null */
    fun getUserBirthday(context: Context): String? =
        prefs(context).getString(KEY_USER_BIRTHDAY, null)?.takeIf { it.isNotBlank() }

    fun setUserBirthday(context: Context, dateStr: String) {
        prefs(context).edit().putString(KEY_USER_BIRTHDAY, dateStr).apply()
        SyncManager.notifyChanged(context, SyncKeys.ANNIVERSARY)
    }

    // ==================== 与后端的业务数据同步（导出 / 导入） ====================

    /**
     * 导出纪念日起点与用户生日为 JSON 对象字符串（供上传后端）。
     * 推送去重标记 [KEY_PUSH_PREFIX] 属于本机状态，不参与同步。
     */
    fun exportJson(context: Context): String = JSONObject().apply {
        getStartDate(context)?.let { put(KEY_START_DATE, it) }
        getUserBirthday(context)?.let { put(KEY_USER_BIRTHDAY, it) }
    }.toString()

    /** 用后端拉取到的 JSON 覆盖本地纪念日设置（不做上传回调，避免回环）。 */
    fun importJson(context: Context, json: String) {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return
        val editor = prefs(context).edit()
        obj.optString(KEY_START_DATE).takeIf { it.isNotBlank() }
            ?.let { editor.putString(KEY_START_DATE, it) }
        obj.optString(KEY_USER_BIRTHDAY).takeIf { it.isNotBlank() }
            ?.let { editor.putString(KEY_USER_BIRTHDAY, it) }
        editor.apply()
    }

    /**
     * 从纪念日起点（或回退到首次聊天时间戳 [firstChatTime]）到今天的天数。
     * 起点和 firstChatTime 都没有时返回 null，调用方展示"还没开始的故事"。
     * 计数方式：认识当天 = 第 1 天（日历天差 + 1）。
     */
    fun daysSinceStart(context: Context, firstChatTime: Long?): Int? {
        val startDateStr = getStartDate(context)
        val startMillis: Long = when {
            !startDateStr.isNullOrBlank() -> parseDateMillis(startDateStr)
            firstChatTime != null && firstChatTime > 0 -> firstChatTime
            else -> return null
        }
        val startDay = floorToDay(startMillis)
        val todayDay = floorToDay(System.currentTimeMillis())
        if (startDay <= 0L || startDay > todayDay) return null  // 起点在未来（设错），不展示
        val diff = ((todayDay - startDay) / 86_400_000L).toInt()
        return diff + 1
    }

    /** 判断今天是否是 milestone 纪念日 */
    fun isMilestoneDay(days: Int?): Boolean {
        if (days == null || days < 1) return false
        return days in MILESTONES || days % 1000 == 0
    }

    /** 判断今天月日是否匹配用户生日（仅比月日，不比年） */
    fun isBirthdayToday(context: Context, cal: Calendar): Boolean {
        val bd = getUserBirthday(context) ?: return false
        val bdMillis = parseDateMillis(bd)
        if (bdMillis <= 0L) return false
        val bdCal = Calendar.getInstance().apply { timeInMillis = bdMillis }
        return bdCal.get(Calendar.MONTH) == cal.get(Calendar.MONTH) &&
            bdCal.get(Calendar.DAY_OF_MONTH) == cal.get(Calendar.DAY_OF_MONTH)
    }

    /** 今天是否尚未推送过 kind 类型（milestone / birthday / festival:<name>） */
    fun shouldPush(context: Context, kind: String, today: String): Boolean =
        prefs(context).getString(KEY_PUSH_PREFIX + kind, "") != today

    fun markPushed(context: Context, kind: String, today: String) {
        prefs(context).edit().putString(KEY_PUSH_PREFIX + kind, today).apply()
    }

    /** 距离下个 milestone 还有几天（>=1） */
    fun daysToNextMilestone(days: Int): Int {
        val next = MILESTONES.firstOrNull { it > days }
        if (next != null) return next - days
        val nextThousand = ((days / 1000) + 1) * 1000
        return nextThousand - days
    }

    private fun prefs(context: Context) =
        EdenStorage.prefs(context, PREFS_NAME)

    private fun parseDateMillis(s: String): Long =
        runCatching { DATE_FMT.parse(s)?.time ?: 0L }.getOrDefault(0L)

    private fun floorToDay(millis: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }
}
