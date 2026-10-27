package com.example.eden.feature.greeting


import com.example.eden.R
import com.example.eden.core.session.SessionManager
import com.example.eden.core.util.FestivalCalendar
import com.example.eden.data.repository.ProfileRepository
import com.example.eden.domain.model.ChatMessage
import com.example.eden.domain.model.MessageType
import com.example.eden.feature.chat.ChatActivity
import com.example.eden.feature.daily.DailyLoopManager
import com.example.eden.feature.focus.FocusTimerController
import com.example.eden.feature.weather.WeatherRepository
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.eden.domain.character.MikuPersona
import com.example.eden.data.repository.ChatRepository
import java.util.*

/**
 * 主动问候的统一入口。
 *
 * 前台（ChatActivity.onResume）与后台（[GreetingAlarmReceiver] 的 9:30 早安闹钟）都调用
 * [checkAndSendGreeting]，问候不依赖用户打开聊天页，App 未启动时也能送达。
 *
 * 该方法是幂等的：同一天同类型问候只会发送一次（由 [DailyLoopManager] 的标记保证）。
 */
object GreetingNotifier {
    const val CHANNEL_ID = "chat_greeting"
    const val NOTIFICATION_ID = 1001
    const val NOTIFICATION_ID_THOUGHT = 1002
    const val NOTIFICATION_ID_GOODNIGHT = 1003
    const val NOTIFICATION_ID_FOCUS_DONE = 1004

    private const val CHARACTER_ID = MikuPersona.ID

    /**
     * 检查并发送问候（早安 / 久别归来），返回本次是否真的发送了。
     */
    suspend fun checkAndSendGreeting(context: Context): Boolean {
        // 未登录或开关关闭时不打扰用户
        if (!SessionManager.isLoggedIn(context)) return false
        if (!ProfileRepository.isGreetingEnabled(context)) return false

        val shouldSendMorning = DailyLoopManager.shouldSendMorningGreeting(context)
        val shouldSendReturn = DailyLoopManager.shouldSendReturnGreeting(context)
        if (!shouldSendMorning && !shouldSendReturn) return false

        val weather = WeatherRepository.getWeather(context)
        val nickname = ProfileRepository.getCallRaw(context)
        val message = if (shouldSendMorning) {
            buildMorningMessage(weather, nickname)
        } else {
            buildReturnMessage(weather, nickname)
        }

        // 与聊天页共用同一份 Room 存储，保证后台写入的问候在聊天记录里可见
        ChatRepository(context).append(
            characterId = CHARACTER_ID,
            message = ChatMessage(content = message, type = MessageType.TYPE_RECEIVED)
        )
        sendGreetingNotification(context, message)

        if (shouldSendMorning) DailyLoopManager.markMorningSent(context)
        if (shouldSendReturn) DailyLoopManager.markReturnSent(context)
        return true
    }

    /**
     * 检查并发送「想你」推送，返回本次是否真的发送了。
     *
     * 由后台 [GreetingAlarmReceiver] 在 [GreetingScheduler] 设定的每日 12:00–20:00
     * 随机时间点触发。与 [checkAndSendGreeting] 互不依赖——前者靠前台 onResume，
     * 本方法靠闹钟——避免互相抢「今日已发」标记。
     */
    suspend fun checkAndSendThoughtOfYou(context: Context): Boolean {
        if (!SessionManager.isLoggedIn(context)) return false
        if (!ProfileRepository.isGreetingEnabled(context)) return false
        if (!DailyLoopManager.shouldSendThoughtOfYou(context)) return false

        val message = buildThoughtOfYouMessage()
        ChatRepository(context).append(
            characterId = CHARACTER_ID,
            message = ChatMessage(content = message, type = MessageType.TYPE_RECEIVED)
        )
        sendGreetingNotification(context, message, NOTIFICATION_ID_THOUGHT)

        DailyLoopManager.markThoughtOfYouSent(context)
        return true
    }

    /**
     * 从 [MikuPersona.THOUGHT_OF_YOU_LINES] 随机抽一条作为「想你」推送内容。
     * 内容每天都不一样，进一步降低「机械感」。
     */
    private fun buildThoughtOfYouMessage(): String =
        MikuPersona.THOUGHT_OF_YOU_LINES.random()

    /**
     * 检查并发送「晚安」推送，返回本次是否真的发送了。
     *
     * 由后台 [GreetingAlarmReceiver] 在 [GreetingScheduler] 设定的每日 22:00 触发；
     * 是否发送还需通过 [DailyLoopManager.shouldSendGoodNight] 的 1/3 概率放行，
     * 因此并非每晚都会发送。
     */
    suspend fun checkAndSendGoodNight(context: Context): Boolean {
        if (!SessionManager.isLoggedIn(context)) return false
        if (!ProfileRepository.isGreetingEnabled(context)) return false
        if (!DailyLoopManager.shouldSendGoodNight(context)) return false

        val message = MikuPersona.GOODNIGHT_LINES.random()
        ChatRepository(context).append(
            characterId = CHARACTER_ID,
            message = ChatMessage(content = message, type = MessageType.TYPE_RECEIVED)
        )
        sendGreetingNotification(context, message, NOTIFICATION_ID_GOODNIGHT)

        DailyLoopManager.markGoodNightSent(context)
        return true
    }

    /**
     * 一段专注结束时，让角色主动在聊天里说一句话，并推送通知（点击进入 [ChatActivity]）。
     *
     * 与「早安 / 想你 / 晚安」不同，这不是定时问候，而是由 [FocusTimerController] 在专注段
     * 结束时一次性触发，因此不受 [DailyLoopManager] 的「今日已发」标记约束，也不写入问候标记。
     */
    suspend fun notifyFocusDone(context: Context) {
        if (!SessionManager.isLoggedIn(context)) return

        val message = MikuPersona.FOCUS_DONE_LINES.random()
        ChatRepository(context).append(
            characterId = CHARACTER_ID,
            message = ChatMessage(content = message, type = MessageType.TYPE_RECEIVED)
        )
        sendGreetingNotification(context, message, NOTIFICATION_ID_FOCUS_DONE)
    }

    /**
     * 组装早安问候：称呼 → 天气句（含降温/升温判断）→ 关心句 → 工作日/休息日收尾。
     *
     * 各段从 [MikuPersona] 对应的文案池随机抽取，每天措辞都不一样，避免「固定台词」的机械感。
     * 拿不到城市名时退化为不含城市的天气句；连天气都没有时只保留关心句与收尾句。
     *
     * 改为 internal：聊天页 [ChatActivity] 复用同一套文案，避免前后台两份实现措辞不一致。
     */
    internal fun buildMorningMessage(weather: WeatherRepository.WeatherResult?, nickname: String): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = if (hour in 8..10) "早安" else "上午好"
        val call = if (nickname.isEmpty()) "" else "$nickname，"

        val weatherPart: String = if (weather != null) {
            val city = weather.city
            when {
                city == null ->
                    MikuPersona.MORNING_WEATHER_NO_CITY_LINES.random()
                        .replace("{text}", weather.text)
                        .replace("{temp}", weather.temp.toString())
                weather.tempChange != null && weather.tempChange <= -3 ->
                    MikuPersona.MORNING_WEATHER_DROP_LINES.random()
                        .replace("{city}", city)
                        .replace("{temp}", weather.temp.toString())
                weather.tempChange != null && weather.tempChange >= 3 ->
                    MikuPersona.MORNING_WEATHER_RISE_LINES.random()
                        .replace("{city}", city)
                        .replace("{temp}", weather.temp.toString())
                else ->
                    MikuPersona.MORNING_WEATHER_LINES.random()
                        .replace("{city}", city)
                        .replace("{text}", weather.text)
                        .replace("{temp}", weather.temp.toString())
            }
        } else ""

        val carePart = if (weather != null) {
            when {
                weather.text.contains("雨") || weather.text.contains("雪") ->
                    MikuPersona.MORNING_CARE_RAIN_LINES.random()
                weather.temp <= 10 -> MikuPersona.MORNING_CARE_COLD_LINES.random()
                weather.temp >= 30 -> MikuPersona.MORNING_CARE_HOT_LINES.random()
                else -> MikuPersona.MORNING_CARE_NORMAL_LINES.random()
            }
        } else ""

        val closePart = if (isRestDay()) {
            MikuPersona.MORNING_RESTDAY_LINES.random()
        } else {
            MikuPersona.MORNING_WORKDAY_LINES.random()
        }

        // 天气句句尾不带标点，用「，」与关心句衔接；关心句自带句号，收尾句另起一句。
        val body = buildString {
            if (weatherPart.isNotEmpty()) {
                append(weatherPart)
                if (carePart.isNotEmpty()) append("，")
            }
            append(carePart)
        }
        return "$greeting……$call$body$closePart"
    }

    /** 周末或节日视为休息日，其余为工作日。 */
    private fun isRestDay(): Boolean {
        val cal = Calendar.getInstance()
        val weekend = cal.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY ||
            cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
        return weekend || FestivalCalendar.todayFestival(cal) != null
    }

    /**
     * 久别归来的关心文案，改为 internal 供聊天页复用。
     */
    internal fun buildReturnMessage(weather: WeatherRepository.WeatherResult?, nickname: String): String {
        val weatherInfo = if (weather != null) "外面${weather.text}，温度${weather.temp}℃。" else ""
        val call = if (nickname.isEmpty()) "你" else nickname
        val lines = listOf(
            "${call}回来了……我等你很久了。$weatherInfo",
            "好久不见……我还以为${call}今天不来了。$weatherInfo",
            "终于又见到${call}了……$weatherInfo"
        )
        return lines.random() + "（微微低头）"
    }

    /** 创建通知渠道，重复调用安全。 */
    fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "三玖的问候", NotificationManager.IMPORTANCE_HIGH)
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun sendGreetingNotification(
        context: Context,
        message: String,
        notificationId: Int = NOTIFICATION_ID
    ) {
        // 「消息通知」开关关闭时不弹系统通知，但消息本身仍会出现在聊天列表里
        if (!ProfileRepository.isNotificationEnabled(context)) return
        ensureNotificationChannel(context)

        val intent = Intent(context, ChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
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
