package com.example.eden

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「今日陪伴时长」统计。
 *
 * 原实现用 `today_minutes` 存分钟，只在退出聊天页时落盘，且不足 1 分钟的余数会被丢弃；
 * 这里改为**秒级**存储，并支持随时累加（聊天页每 30 秒 flush 一次），
 * 这样长时间停留在聊天页时返回主界面也能看到最新时长。
 *
 * 存储：SharedPreferences `company_prefs`
 *   - `last_date`    ：最后一次统计的日期（yyyy-MM-dd），跨天自动归零
 *   - `today_seconds`：当天累计陪伴秒数
 */
object CompanyTimeManager {

    private const val PREFS_NAME = "company_prefs"
    private const val KEY_DATE = "last_date"
    private const val KEY_SECONDS = "today_seconds"

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    private fun prefs(context: Context) =
        EdenStorage.prefs(context, PREFS_NAME)

    /** 读取今日累计陪伴秒数（跨天返回 0，不会读到昨天的数据） */
    fun getTodaySeconds(context: Context): Int {
        val p = prefs(context)
        return if (p.getString(KEY_DATE, "") == today()) p.getInt(KEY_SECONDS, 0) else 0
    }

    /** 累加陪伴秒数（seconds <= 0 时直接忽略，避免无意义的磁盘写） */
    fun addSeconds(context: Context, seconds: Int) {
        if (seconds <= 0) return
        val p = prefs(context)
        val date = today()
        val base = if (p.getString(KEY_DATE, "") == date) p.getInt(KEY_SECONDS, 0) else 0
        p.edit()
            .putString(KEY_DATE, date)
            .putInt(KEY_SECONDS, base + seconds)
            .apply()
    }

    /** 把秒数格式化成「x小时y分钟」/「y分钟」 */
    fun format(totalSeconds: Int): String {
        val totalMinutes = totalSeconds / 60
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}小时${minutes}分钟" else "${minutes}分钟"
    }
}
