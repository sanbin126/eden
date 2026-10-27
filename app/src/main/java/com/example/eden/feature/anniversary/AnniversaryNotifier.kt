package com.example.eden.feature.anniversary


import com.example.eden.R
import com.example.eden.core.session.SessionManager
import com.example.eden.core.util.FestivalCalendar
import com.example.eden.data.repository.ProfileRepository
import com.example.eden.domain.model.ChatMessage
import com.example.eden.domain.model.MessageType
import com.example.eden.feature.chat.ChatActivity
import com.example.eden.feature.daily.DailyLoopManager
import com.example.eden.feature.greeting.GreetingAlarmReceiver
import com.example.eden.feature.greeting.GreetingNotifier
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.eden.domain.character.MikuPersona
import com.example.eden.data.repository.ChatRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 纪念日推送的统一入口。
 *
 * 与 [GreetingNotifier.checkAndSendGreeting] 同构：登录校验 → 开关校验 → 生成文案 →
 * 写 ChatRepository → 发系统通知 → 标记已发。区别在于触发时机：纪念日推送只在
 * milestone / 生日 / 节日当天触发，且三类各自独立去重，可在同一天各推一条，
 * 让仪式感分明。
 *
 * 由 [GreetingAlarmReceiver] 在每日 9:00 闹钟触发后调用，无需新增 AlarmManager /
 * Receiver。推送失败不影响次日闹钟续接（[GreetingAlarmReceiver] 里独立 try/catch）。
 */
object AnniversaryNotifier {
    private const val CHARACTER_ID = MikuPersona.ID

    // 通知 ID 1001（早安/归来）、1002（想你）、1003（晚安）已被 [GreetingNotifier] 占用；
    // 这里用 1004+。三类各占一个 ID，避免同一天与主动问候或彼此之间互相覆盖。
    private const val NOTIFICATION_ID_MILESTONE = 1004
    private const val NOTIFICATION_ID_BIRTHDAY = 1005
    private const val NOTIFICATION_ID_FESTIVAL = 1006

    private const val KIND_MILESTONE = "milestone"
    private const val KIND_BIRTHDAY = "birthday"
    private const val KIND_FESTIVAL_PREFIX = "festival:"

    /**
     * 检查今天是否命中 milestone / 生日 / 节日，对每个命中项各推一条。
     * 返回是否至少推送过一条。
     */
    suspend fun checkAndSend(context: Context): Boolean {
        // 与早安问候共用同一总开关：关掉主动问候就不打扰用户
        if (!SessionManager.isLoggedIn(context)) return false
        if (!ProfileRepository.isGreetingEnabled(context)) return false

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val cal = Calendar.getInstance()
        val firstChatTime = ChatRepository(context).firstChatTime(CHARACTER_ID)
        val days = DailyLoopManager.daysSinceStart(context, firstChatTime)

        // milestone：第 1/100/200/365/500/730/1000... 天
        val milestoneMsg: String? = if (DailyLoopManager.isMilestoneDay(days)) {
            val d = days ?: 1
            MikuPersona.ANNIVERSARY_LINES.random().replace("{DAYS}", d.toString())
        } else null
        val sentMilestone = sendIfPending(context, KIND_MILESTONE, today, milestoneMsg, NOTIFICATION_ID_MILESTONE)

        // 生日：仅按月日匹配，不取年龄
        val birthdayMsg: String? = if (DailyLoopManager.isBirthdayToday(context, cal)) {
            MikuPersona.BIRTHDAY_LINES.random()
        } else null
        val sentBirthday = sendIfPending(context, KIND_BIRTHDAY, today, birthdayMsg, NOTIFICATION_ID_BIRTHDAY)

        // 节日：公历 + 农历
        val festival = FestivalCalendar.todayFestival(cal)
        val festivalMsg: String? = festival?.let { MikuPersona.FESTIVAL_LINES[it]?.random() }
        val sentFestival = sendIfPending(
            context, KIND_FESTIVAL_PREFIX + festival, today, festivalMsg, NOTIFICATION_ID_FESTIVAL
        )

        return sentMilestone || sentBirthday || sentFestival
    }

    /**
     * 当日某类还未推送过且文案非空时，写聊天记录 + 发系统通知 + 标记已发。
     */
    private suspend fun sendIfPending(
        context: Context,
        kind: String,
        today: String,
        message: String?,
        notificationId: Int
    ): Boolean {
        if (message == null) return false
        if (!DailyLoopManager.shouldPush(context, kind, today)) return false

        ChatRepository(context).append(
            characterId = CHARACTER_ID,
            message = ChatMessage(content = message, type = MessageType.TYPE_RECEIVED)
        )
        sendNotification(context, message, notificationId)
        DailyLoopManager.markPushed(context, kind, today)
        return true
    }

    /**
     * 发系统通知。复用 [GreetingNotifier.CHANNEL_ID] 与 [GreetingNotifier.ensureNotificationChannel]，
     * 通知构建逻辑与 [GreetingNotifier.sendGreetingNotification] 同款（标题"中野三玖"，点击跳 ChatActivity）。
     */
    private fun sendNotification(context: Context, message: String, notificationId: Int) {
        // 「消息通知」开关关闭时不弹系统通知，但消息本身仍会出现在聊天列表里
        if (!ProfileRepository.isNotificationEnabled(context)) return
        GreetingNotifier.ensureNotificationChannel(context)

        val intent = Intent(context, ChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val notification = NotificationCompat.Builder(context, GreetingNotifier.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle(context.getString(R.string.main_character_name))
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(notificationId, notification)
    }
}
