package com.example.eden

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 接收 [GreetingScheduler] 注册的精确闹钟，按 [GreetingScheduler.EXTRA_KIND] 分派到
 * 对应的主动消息：随机时段「想你」或 22:00「晚安」，随后重新设定次日闹钟。
 *
 * 用 [goAsync] 让系统等我们的协程完成再回收 BroadcastReceiver（默认 onReceive
 * 必须在 ~10 秒内返回，否则会被系统杀掉）。
 */
class GreetingAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        val kind = intent?.getStringExtra(GreetingScheduler.EXTRA_KIND)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (kind) {
                    GreetingScheduler.KIND_GOODNIGHT -> {
                        try {
                            GreetingNotifier.checkAndSendGoodNight(context)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    else -> {
                        // 随机时段的「想你」推送。此前漏掉了 checkAndSendThoughtOfYou，
                        // 导致该推送从未真正发出——现补回。
                        try {
                            GreetingNotifier.checkAndSendThoughtOfYou(context)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        // 早安 / 久别归来在同一随机时段顺带检查一次。
                        try {
                            GreetingNotifier.checkAndSendGreeting(context)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        // 纪念日推送独立 try/catch：失败不影响其它推送与次日闹钟续接
                        try {
                            AnniversaryNotifier.checkAndSend(context)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            } finally {
                // 不管发没发，都要把次日闹钟续上，否则一次失败就再也不触发了
                runCatching {
                    if (kind == GreetingScheduler.KIND_GOODNIGHT) {
                        GreetingScheduler.rescheduleGoodNight(context)
                    } else {
                        GreetingScheduler.rescheduleThought(context)
                    }
                }
                pendingResult.finish()
            }
        }
    }
}

/**
 * 设备重启后重新注册每日闹钟（AlarmManager 的闹钟不会跨重启存活）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                GreetingScheduler.schedule(context)
            }
        }
    }
}
