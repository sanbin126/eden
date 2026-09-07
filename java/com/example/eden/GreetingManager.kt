package com.example.eden

import android.content.Context
import java.text.SimpleDateFormat
import java.util.*

object GreetingManager {
    private const val PREFS_NAME = "greeting_prefs"
    private const val KEY_MORNING_SENT = "morning_sent_date"
    private const val KEY_LAST_EXIT_TIME = "last_exit_time"
    private const val KEY_RETURN_SENT_TODAY = "return_sent_today"

    /**
     * 检测是否应该发送早安问候
     * 条件：当前时间 8:00-11:00，今天尚未发送过
     */
    fun shouldSendMorningGreeting(context: Context): Boolean {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        if (hour < 8 || hour > 11) return false

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val lastDate = prefs.getString(KEY_MORNING_SENT, "")
        return today != lastDate
    }

    /**
     * 标记早安已发送
     */
    fun markMorningSent(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        prefs.edit().putString(KEY_MORNING_SENT, today).apply()
    }

    /**
     * 记录退出时间
     */
    fun recordExitTime(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_LAST_EXIT_TIME, System.currentTimeMillis()).apply()
    }

    /**
     * 检测是否应该发送长时间未见的关心
     * 条件：距离上次退出超过2小时，且今天尚未发送过这种关心
     */
    fun shouldSendReturnGreeting(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        prefs.edit().putString(KEY_RETURN_SENT_TODAY, today).apply()
    }
}