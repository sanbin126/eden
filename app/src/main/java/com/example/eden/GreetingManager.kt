package com.example.eden

import android.content.Context
import java.text.SimpleDateFormat
import java.util.*

object GreetingManager {
    private const val PREFS_NAME = "greeting_prefs"
    private const val KEY_MORNING_SENT = "morning_sent_date"
    private const val KEY_LAST_EXIT_TIME = "last_exit_time"
    private const val KEY_RETURN_SENT_TODAY = "return_sent_today"
    private const val KEY_THOUGHT_OF_YOU_SENT = "thought_of_you_sent_date"
    private const val KEY_GOODNIGHT_SENT = "goodnight_sent_date"

    /**
     * 检测是否应该发送早安问候
     * 条件：当前时间 8:00-11:00，今天尚未发送过
     */
    fun shouldSendMorningGreeting(context: Context): Boolean {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        if (hour < 8 || hour > 11) return false

        // 问候去重记录按账号分区，切号后各自独立
        val prefs = EdenStorage.prefs(context, PREFS_NAME)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val lastDate = prefs.getString(KEY_MORNING_SENT, "")
        return today != lastDate
    }

    /**
     * 标记早安已发送
     */
    fun markMorningSent(context: Context) {
        val prefs = EdenStorage.prefs(context, PREFS_NAME)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        prefs.edit().putString(KEY_MORNING_SENT, today).apply()
    }

    /**
     * 记录退出时间
     */
    fun recordExitTime(context: Context) {
        val prefs = EdenStorage.prefs(context, PREFS_NAME)
        prefs.edit().putLong(KEY_LAST_EXIT_TIME, System.currentTimeMillis()).apply()
    }

    /**
     * 检测是否应该发送长时间未见的关心
     * 条件：距离上次退出超过2小时，且今天尚未发送过这种关心
     */
    fun shouldSendReturnGreeting(context: Context): Boolean {
        val prefs = EdenStorage.prefs(context, PREFS_NAME)
        val lastExit = prefs.getLong(KEY_LAST_EXIT_TIME, 0)
        if (lastExit == 0L) return false
        val diff = System.currentTimeMillis() - lastExit
        if (diff < 2 * 60 * 60 * 1000L) return false

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val returnDate = prefs.getString(KEY_RETURN_SENT_TODAY, "")
        return today != returnDate
    }

    /**
     * 标记今日已发送返回关心
     */
    fun markReturnSent(context: Context) {
        val prefs = EdenStorage.prefs(context, PREFS_NAME)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        prefs.edit().putString(KEY_RETURN_SENT_TODAY, today).apply()
    }

    /**
     * 检测是否应该发送「想你」推送。
     *
     * 触发条件：当前时间在 12:00–20:00 之间，且今天尚未发送过。
     * 与 [GreetingScheduler] 的随机时段闹钟配套——闹钟触发时间已经随机化，
     * 这里只负责「今天窗口内还没发过」的去重判断，避免前台 [com.example.eden.ChatActivity]
     * 偶发触发或闹钟重设后重复推送。
     */
    fun shouldSendThoughtOfYou(context: Context): Boolean {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        if (hour < 12 || hour >= 20) return false

        val prefs = EdenStorage.prefs(context, PREFS_NAME)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val lastDate = prefs.getString(KEY_THOUGHT_OF_YOU_SENT, "")
        return today != lastDate
    }

    /**
     * 标记今日已发送「想你」推送。
     */
    fun markThoughtOfYouSent(context: Context) {
        val prefs = EdenStorage.prefs(context, PREFS_NAME)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        prefs.edit().putString(KEY_THOUGHT_OF_YOU_SENT, today).apply()
    }

    /**
     * 检测是否应该发送「晚安」推送。
     *
     * 触发条件：进入夜间时段（21:00 之后，含跨零点到次日凌晨）且今天尚未发送过。
     * 放宽到 21:00 起是为了容忍 Doze / 后台限制导致的闹钟延迟——22:00 的闹钟晚几分钟
     * 触发时仍算夜间，不至于因为差几分钟就发不出去。
     */
    fun shouldSendGoodNight(context: Context): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour in 5 until 21) return false

        val prefs = EdenStorage.prefs(context, PREFS_NAME)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val lastDate = prefs.getString(KEY_GOODNIGHT_SENT, "")
        return today != lastDate
    }

    /**
     * 标记今日已发送「晚安」推送。
     */
    fun markGoodNightSent(context: Context) {
        val prefs = EdenStorage.prefs(context, PREFS_NAME)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        prefs.edit().putString(KEY_GOODNIGHT_SENT, today).apply()
    }
}
