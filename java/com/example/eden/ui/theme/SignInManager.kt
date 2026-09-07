package com.example.eden

import android.content.Context
import java.text.SimpleDateFormat
import java.util.*

object SignInManager {
    private const val PREFS_NAME = "sign_in_prefs"
    private const val KEY_SIGNED_DATES = "signed_dates"

    /**
     * 获取已签到日期集合
     */
    fun getSignedDates(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_SIGNED_DATES, emptySet()) ?: emptySet()
    }

    /**
     * 判断指定日期是否已签到
     */
    fun isDateSigned(context: Context, dateStr: String): Boolean {
        return getSignedDates(context).contains(dateStr)
    }

    /**
     * 判断今天是否已签到
     */
    fun isTodaySigned(context: Context): Boolean {
        val today = getTodayStr()
        return isDateSigned(context, today)
    }

    /**
     * 执行签到，返回签到后的连续天数
     */
    fun signToday(context: Context): Int {
        val today = getTodayStr()
        if (isDateSigned(context, today)) return getContinuousDays(context)

        val signedDates = getSignedDates(context).toMutableSet()
        signedDates.add(today)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_SIGNED_DATES, signedDates)
            .apply()

        return getContinuousDays(context)
    }

    /**
     * 获取连续签到天数（从今天往前推）
     */
    fun getContinuousDays(context: Context): Int {
        val signedDates = getSignedDates(context)
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val calendar = Calendar.getInstance()
        var count = 0
        while (true) {
            val dateStr = sdf.format(calendar.time)
            if (signedDates.contains(dateStr)) {
                count++
                calendar.add(Calendar.DAY_OF_YEAR, -1)
            } else {
                break
            }
        }
        return count
    }

    private fun getTodayStr(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }
}