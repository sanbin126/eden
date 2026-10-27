package com.example.eden.feature.greeting

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar
import kotlin.random.Random

/**
 * 注册主动消息的每日精确闹钟（「早安」固定 9:30 + 「想你」随机时段 + 「晚安」固定 22:00）。
 *
 * 三条闹钟分工：
 * - **早安**：每日 9:30 触发，走 [com.example.eden.feature.greeting.GreetingNotifier.checkAndSendGreeting]。
 *   若用户 10:00 前主动打开聊天页，前台也会补发一次，两边共用当天去重标记；
 * - **想你**：每日在 12:00–20:00 之间随机一个时间点（精确到分钟）触发，内容从
 *   [com.example.eden.domain.character.MikuPersona.THOUGHT_OF_YOU_LINES] 随机抽一条；
 * - **晚安**：每日 22:00 触发，内容从 [com.example.eden.domain.character.MikuPersona.GOODNIGHT_LINES]
 *   随机抽一条；是否真的发送由 [com.example.eden.feature.daily.DailyLoopManager.shouldSendGoodNight] 以
 *   1/3 概率决定，因此并非每天都会收到。
 *
 * 三条闹钟用不同的 requestCode 与 [EXTRA_KIND] 区分，各自独立续接。App 未打开、进程
 * 被回收也能触发；设备重启后由 [BootReceiver] 重新设闹钟。触发后由 [GreetingAlarmReceiver]
 * 按 [EXTRA_KIND] 分派到对应逻辑，并自动 reschedule 次日闹钟，形成自循环。
 */
object GreetingScheduler {
    private const val REQUEST_CODE_THOUGHT = 1001
    private const val REQUEST_CODE_GOODNIGHT = 1002
    private const val REQUEST_CODE_MORNING = 1003

    /** 闹钟附带类型标记，供 [GreetingAlarmReceiver] 分派 */
    const val EXTRA_KIND = "extra_kind"
    const val KIND_THOUGHT = "thought"
    const val KIND_GOODNIGHT = "goodnight"
    const val KIND_MORNING = "morning"

    /** 「早安」固定触发时间：每日 9:30 */
    private const val MORNING_HOUR = 9
    private const val MORNING_MINUTE = 30

    /** 「想你」推送时间窗口：[WINDOW_START_HOUR, WINDOW_END_HOUR) 之间随机到分钟 */
    private const val WINDOW_START_HOUR = 12
    private const val WINDOW_END_HOUR = 20

    /** 「晚安」推送固定时间点 */
    private const val GOODNIGHT_HOUR = 22

    /**
     * 幂等注册三条闹钟（早安 + 想你 + 晚安）。重复调用安全：同一 PendingIntent +
     * FLAG_UPDATE_CURRENT 会刷新已注册闹钟。
     */
    fun schedule(context: Context) {
        setAlarm(context, REQUEST_CODE_MORNING, KIND_MORNING, nextMorningTime())
        setAlarm(context, REQUEST_CODE_THOUGHT, KIND_THOUGHT, nextThoughtTime())
        setAlarm(context, REQUEST_CODE_GOODNIGHT, KIND_GOODNIGHT, nextGoodNightTime())
    }

    /**
     * 「早安」闹钟触发后调用，把下一次对齐到次日 9:30。
     */
    fun rescheduleMorning(context: Context) {
        val calendar = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, MORNING_HOUR)
            set(Calendar.MINUTE, MORNING_MINUTE)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        setAlarm(context, REQUEST_CODE_MORNING, KIND_MORNING, calendar.timeInMillis)
    }

    /**
     * 「想你」闹钟触发后调用，把下一次对齐到次日 12:00–20:00 之间的随机分钟。
     */
    fun rescheduleThought(context: Context) {
        val calendar = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, WINDOW_START_HOUR)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            val windowMinutes = (WINDOW_END_HOUR - WINDOW_START_HOUR) * 60
            add(Calendar.MINUTE, Random.nextInt(windowMinutes))
        }
        setAlarm(context, REQUEST_CODE_THOUGHT, KIND_THOUGHT, calendar.timeInMillis)
    }

    /**
     * 「晚安」闹钟触发后调用，把下一次对齐到次日 22:00。
     */
    fun rescheduleGoodNight(context: Context) {
        val calendar = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, GOODNIGHT_HOUR)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        setAlarm(context, REQUEST_CODE_GOODNIGHT, KIND_GOODNIGHT, calendar.timeInMillis)
    }

    /**
     * 计算下一次「想你」触发时间：
     * - 若当前时刻已晚于今日 20:00，则取次日 12:00–20:00 之间随机一刻；
     * - 否则取今日窗口内剩余时段（当前时刻 ~ 20:00）的一个随机一刻，
     *   保证「今天至少还能收到一次」。
     */
    private fun nextThoughtTime(): Long {
        val now = System.currentTimeMillis()
        val todayWindowEnd = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, WINDOW_END_HOUR)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        if (now >= todayWindowEnd) {
            val calendar = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, WINDOW_START_HOUR)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                val windowMinutes = (WINDOW_END_HOUR - WINDOW_START_HOUR) * 60
                add(Calendar.MINUTE, Random.nextInt(windowMinutes))
            }
            return calendar.timeInMillis
        }

        // 今日窗口尚未结束：在「当前时刻 + 1 分钟」到「今日 20:00」之间随机一刻。
        val earliest = Calendar.getInstance().apply {
            add(Calendar.MINUTE, 1)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val spanMillis = todayWindowEnd - earliest
        val randomOffset = Random.nextLong(spanMillis.coerceAtLeast(1L))
        return earliest + randomOffset
    }

    /**
     * 计算下一次「早安」触发时间：今日 9:30（若已过则次日 9:30）。
     */
    private fun nextMorningTime(): Long {
        val now = System.currentTimeMillis()
        val todayAt = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, MORNING_HOUR)
            set(Calendar.MINUTE, MORNING_MINUTE)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (now >= todayAt.timeInMillis) {
            todayAt.add(Calendar.DAY_OF_YEAR, 1)
        }
        return todayAt.timeInMillis
    }

    /**
     * 计算下一次「晚安」触发时间：今日 22:00（若已过则次日 22:00）。
     */
    private fun nextGoodNightTime(): Long {
        val now = System.currentTimeMillis()
        val todayAt = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, GOODNIGHT_HOUR)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (now >= todayAt.timeInMillis) {
            todayAt.add(Calendar.DAY_OF_YEAR, 1)
        }
        return todayAt.timeInMillis
    }

    private fun setAlarm(context: Context, requestCode: Int, kind: String, triggerAtMillis: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, GreetingAlarmReceiver::class.java).apply {
            putExtra(EXTRA_KIND, kind)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        // Android 12+ 需要精确闹钟权限；权限不足时回退到 setAndAllowWhileIdle，
        // 仍能在 Doze 下"大致"准点触发（误差通常在几分钟内）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent
            )
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }
}
