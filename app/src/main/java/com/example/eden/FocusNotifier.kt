package com.example.eden

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * 专注陪伴的完成提醒。
 *
 * 与 [GreetingNotifier] 分开：问候是「三玖主动找你」，这里是「番茄钟到点的工具提醒」，
 * 渠道、文案、点击目标都不同，避免混在一起关闭。
 */
object FocusNotifier {

    const val CHANNEL_ID = "focus_timer"
    private const val NOTIFICATION_ID_COMPLETE = 2001
    private const val NOTIFICATION_ID_BREAK = 2002

    /** 创建通知渠道，重复调用安全。 */
    fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "专注提醒", NotificationManager.IMPORTANCE_HIGH)
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    /** 一段专注完成：提醒用户去休息一下。 */
    fun notifyFocusComplete(context: Context, taskTitle: String) {
        val call = NicknameManager.getCall(context)
        val text = if (taskTitle.isBlank()) {
            "${call}专注完成啦，去休息一下～"
        } else {
            "「$taskTitle」的番茄完成啦，${call}去休息一下～"
        }
        notify(context, "专注完成 · 番茄钟", text, NOTIFICATION_ID_COMPLETE)
    }

    /** 一段休息结束：提醒可以继续专注了。 */
    fun notifyBreakComplete(context: Context) {
        val call = NicknameManager.getCall(context)
        notify(context, "休息结束 · 番茄钟", "$call，准备好开始下一段专注了吗？", NOTIFICATION_ID_BREAK)
    }

    private fun notify(context: Context, title: String, message: String, id: Int) {
        if (!SettingsManager.isNotificationEnabled(context)) return
        ensureNotificationChannel(context)

        val intent = Intent(context, FocusTimerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(id, notification)
    }
}
