package com.example.eden.feature.daily

import android.content.Context
import com.example.eden.core.session.SyncKeys
import com.example.eden.core.session.SessionManager
import com.example.eden.core.storage.EdenStorage
import com.example.eden.feature.greeting.GreetingAlarmReceiver
import com.example.eden.feature.greeting.GreetingScheduler
import com.example.eden.feature.signin.SignInRepository
import java.text.SimpleDateFormat
import java.util.*
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject

/**
 * 每日节奏的唯一入口：每日循环任务、主动问候的时段去重、纪念日与里程碑。
 *
 * 原先的 DailyLoopManager / DailyLoopManager / DailyLoopManager 合并于此，
 * 三者本质上都在回答同一个问题——「今天该不该主动找她」。
 */
/**
 * 「每日任务」的六个条目。
 *
 * 顺序仅用于展示与推荐（[DailyLoopManager.nextStep]），实际完成不强制先后，
 * 用户任意顺序完成都算数。
 */
enum class LoopStep(val id: String, val title: String) {
    SIGN_IN("sign_in", "和三玖打个招呼，完成今天的签到"),
    TOUCH("touch", "摸摸三玖的头，让她开心一下"),
    GAME("game", "和三玖玩一局小游戏"),
    CHAT("chat", "和三玖聊上几句，说说今天的事"),
    FOCUS("focus", "陪三玖一起专注一段计时"),
    FEED("feed", "给三玖投喂或送出一件礼物"),
}

object DailyLoopManager {


/**
 * 每日任务进度记录（本机当日状态，不参与 [SessionManager] 同步）。
 *
 * 记住三件事：今天哪些条目已完成、「全部完成」奖励是否已领取，以及主页最近一次
 * 已经给过指引的条目（用于去重，避免回到主页就反复弹同一句引导）。
 * 周期按自然日 `yyyy-MM-dd` 划分，跨天自动重来。
 */

    // ==================== 来自 DailyLoopManager ====================
    private const val LOOP_PREFS_NAME = "daily_loop_prefs"
    private const val KEY_STATE = "loop_state"

    /** 六项任务全部完成、在任务页手动领取时发放的「円」。 */
    const val ALL_DONE_REWARD_YEN = 20

    /** 标记某节点今日已完成；返回 true 表示这是一次新的标记（此前未完成）。 */
    fun markDone(context: Context, step: LoopStep): Boolean {
        val state = readState(context)
        if (state.steps.contains(step.id)) return false
        state.steps.add(step.id)
        writeState(context, state)
        return true
    }

    fun isDone(context: Context, step: LoopStep): Boolean =
        readState(context).steps.contains(step.id)

    /** 今日第一个尚未完成的节点；全部完成则返回 null。 */
    fun nextStep(context: Context): LoopStep? {
        val done = readState(context).steps
        return LoopStep.values().firstOrNull { !done.contains(it.id) }
    }

    /** 今日进度：已完成数 to 总数。 */
    fun todayProgress(context: Context): Pair<Int, Int> {
        val done = readState(context).steps
        val total = LoopStep.values().size
        val count = LoopStep.values().count { done.contains(it.id) }
        return count to total
    }

    /** 今日六项是否已全部完成。 */
    fun allDone(context: Context): Boolean = nextStep(context) == null

    /** 今日「全部完成」奖励是否已领取（跨天自动重置为未领取）。 */
    fun isRewardClaimed(context: Context): Boolean = readState(context).rewardClaimed

    /**
     * 领取「全部完成」奖励。仅当六项都已完成且今日尚未领取时返回 true，
     * 重复点击 / 并发调用只会成功一次，由调用方负责发「円」。
     */
    fun claimReward(context: Context): Boolean {
        val state = readState(context)
        if (state.rewardClaimed || state.steps.size < LoopStep.values().size) return false
        state.rewardClaimed = true
        writeState(context, state)
        return true
    }

    /** 主页最近一次已指引的节点 id（跨天自动失效）。 */
    fun lastGuided(context: Context): String? = readState(context).guided

    /** 记录主页已就某个节点给过指引，避免重复弹出。 */
    fun markGuided(context: Context, step: LoopStep) {
        val state = readState(context)
        state.guided = step.id
        writeState(context, state)
    }

    // ==================== 内部实现 ====================

    private class State(val period: String) {
        val steps = mutableSetOf<String>()
        var rewardClaimed = false
        var guided: String? = null
    }

    private fun readState(context: Context): State {
        val today = todayStr()
        val obj = runCatching { JSONObject(prefs(context).getString(KEY_STATE, "") ?: "") }
            .getOrElse { JSONObject() }
        if (obj.optString("period") != today) return State(today)

        val state = State(today)
        val arr = obj.optJSONArray("steps")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val id = arr.optString(i)
                if (id.isNotEmpty()) state.steps.add(id)
            }
        }
        state.rewardClaimed = obj.optBoolean("reward_claimed", false)
        state.guided = obj.optString("guided").takeIf { it.isNotEmpty() }
        return state
    }

    private fun writeState(context: Context, state: State) {
        val arr = JSONArray()
        state.steps.forEach { arr.put(it) }
        val obj = JSONObject()
            .put("period", state.period)
            .put("steps", arr)
            .put("reward_claimed", state.rewardClaimed)
            .put("guided", state.guided ?: "")
        prefs(context).edit().putString(KEY_STATE, obj.toString()).apply()
    }

    private fun todayStr(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    private fun prefs(context: Context) =
        EdenStorage.prefs(context, LOOP_PREFS_NAME)

    // ==================== 来自 DailyLoopManager ====================
    private const val GREETING_PREFS_NAME = "greeting_prefs"
    private const val KEY_MORNING_SENT = "morning_sent_date"
    private const val KEY_LAST_EXIT_TIME = "last_exit_time"
    private const val KEY_RETURN_SENT_TODAY = "return_sent_today"
    private const val KEY_THOUGHT_OF_YOU_SENT = "thought_of_you_sent_date"
    private const val KEY_GOODNIGHT_SENT = "goodnight_sent_date"

    /**
     * 检测是否应该发送早安问候。
     *
     * 条件：当前时间在 10:00 之前（5:00 起，避免与 21:00–5:00 的夜间「晚安」时段重叠），
     * 且今天尚未发送过。
     *
     * 早安的正式触发点是 [GreetingScheduler] 每日 9:30 的固定闹钟；若用户在 10:00 前
     * （哪怕 9:00 前）主动打开聊天页，也走同一判断即时补发。两条路径共用 KEY_MORNING_SENT
     * 去重，当天只出现一次：先触发的先发，后触发的被去重跳过。
     */
    fun shouldSendMorningGreeting(context: Context): Boolean {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        if (hour < 5 || hour >= 10) return false

        // 问候去重记录按账号分区，切号后各自独立
        val prefs = EdenStorage.prefs(context, GREETING_PREFS_NAME)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val lastDate = prefs.getString(KEY_MORNING_SENT, "")
        return today != lastDate
    }

    /**
     * 标记早安已发送
     */
    fun markMorningSent(context: Context) {
        val prefs = EdenStorage.prefs(context, GREETING_PREFS_NAME)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        prefs.edit().putString(KEY_MORNING_SENT, today).apply()
    }

    /**
     * 记录退出时间
     */
    fun recordExitTime(context: Context) {
        val prefs = EdenStorage.prefs(context, GREETING_PREFS_NAME)
        prefs.edit().putLong(KEY_LAST_EXIT_TIME, System.currentTimeMillis()).apply()
    }

    /** 上次退出时间；无记录返回 0（供 WorldState 派生「久别」）。 */
    fun lastExitTime(context: Context): Long =
        EdenStorage.prefs(context, GREETING_PREFS_NAME).getLong(KEY_LAST_EXIT_TIME, 0L)

    /**
     * 检测是否应该发送长时间未见的关心
     * 条件：距离上次退出超过2小时，且今天尚未发送过这种关心
     */
    fun shouldSendReturnGreeting(context: Context): Boolean {
        val prefs = EdenStorage.prefs(context, GREETING_PREFS_NAME)
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
        val prefs = EdenStorage.prefs(context, GREETING_PREFS_NAME)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        prefs.edit().putString(KEY_RETURN_SENT_TODAY, today).apply()
    }

    /**
     * 检测是否应该发送「想你」推送。
     *
     * 触发条件：当前时间在 12:00–20:00 之间，且今天尚未发送过。
     * 与 [GreetingScheduler] 的随机时段闹钟配套——闹钟触发时间已经随机化，
     * 这里只负责「今天窗口内还没发过」的去重判断，避免前台 [com.example.eden.feature.chat.ChatActivity]
     * 偶发触发或闹钟重设后重复推送。
     */
    fun shouldSendThoughtOfYou(context: Context): Boolean {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        if (hour < 12 || hour >= 20) return false

        val prefs = EdenStorage.prefs(context, GREETING_PREFS_NAME)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val lastDate = prefs.getString(KEY_THOUGHT_OF_YOU_SENT, "")
        return today != lastDate
    }

    /**
     * 标记今日已发送「想你」推送。
     */
    fun markThoughtOfYouSent(context: Context) {
        val prefs = EdenStorage.prefs(context, GREETING_PREFS_NAME)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        prefs.edit().putString(KEY_THOUGHT_OF_YOU_SENT, today).apply()
    }

    /**
     * 检测是否应该发送「晚安」推送。
     *
     * 触发条件：
     * 1. 进入夜间时段（21:00 之后，含跨零点到次日凌晨）——放宽到 21:00 起是为了容忍
     *    Doze / 后台限制导致的闹钟延迟，22:00 的闹钟晚几分钟触发时仍算夜间；
     * 2. 今天尚未发送过；
     * 3. 以 1/3 概率放行——晚安不再每天必发，更像真人偶尔想起才道一句。
     *
     * 该判断每天只由 [GreetingAlarmReceiver] 的 22:00 闹钟调用一次，因此概率掷骰每天
     * 只进行一次：没中则今天不再发，次日重新掷。
     */
    fun shouldSendGoodNight(context: Context): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour in 5 until 21) return false

        val prefs = EdenStorage.prefs(context, GREETING_PREFS_NAME)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val lastDate = prefs.getString(KEY_GOODNIGHT_SENT, "")
        if (today == lastDate) return false

        return Random.nextInt(3) == 0
    }

    /**
     * 标记今日已发送「晚安」推送。
     */
    fun markGoodNightSent(context: Context) {
        val prefs = EdenStorage.prefs(context, GREETING_PREFS_NAME)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        prefs.edit().putString(KEY_GOODNIGHT_SENT, today).apply()
    }

/**
 * 纪念日系统的本地状态存储。
 *
 * 三件事：(1) "在一起第 X 天"的起点日期（默认空，回退到首次聊天日期）；
 * (2) 用户生日（公历，按月日判定，不取年龄）；(3) 当日各类推送的去重标记，
 * 镜像 [DailyLoopManager] 的"日期级 key"模式——同一天同 kind 只推一次。
 *
 * 存储用 SharedPreferences，与 [SignInRepository] / [DailyLoopManager] 同款风格，
 * 不需要 Room / Hilt 注入，调用方直接 get / set 即可。
 */

    // ==================== 来自 DailyLoopManager ====================
    private const val ANNIVERSARY_PREFS_NAME = "anniversary_prefs"
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
        anniversaryPrefs(context).getString(KEY_START_DATE, null)?.takeIf { it.isNotBlank() }

    fun setStartDate(context: Context, dateStr: String) {
        anniversaryPrefs(context).edit().putString(KEY_START_DATE, dateStr).apply()
        SessionManager.notifyChanged(context, SyncKeys.ANNIVERSARY)
    }

    /** 用户生日串；未设置返回 null */
    fun getUserBirthday(context: Context): String? =
        anniversaryPrefs(context).getString(KEY_USER_BIRTHDAY, null)?.takeIf { it.isNotBlank() }

    fun setUserBirthday(context: Context, dateStr: String) {
        anniversaryPrefs(context).edit().putString(KEY_USER_BIRTHDAY, dateStr).apply()
        SessionManager.notifyChanged(context, SyncKeys.ANNIVERSARY)
    }

    // ==================== 与后端的业务数据同步（导出 / 导入） ====================

    /**
     * 导出纪念日起点与用户生日为 JSON 对象字符串（供上传后端）。
     * 推送去重标记 [KEY_PUSH_PREFIX] 属于本机状态，不参与同步。
     */
    fun exportAnniversaryJson(context: Context): String = JSONObject().apply {
        getStartDate(context)?.let { put(KEY_START_DATE, it) }
        getUserBirthday(context)?.let { put(KEY_USER_BIRTHDAY, it) }
    }.toString()

    /** 用后端拉取到的 JSON 覆盖本地纪念日设置（不做上传回调，避免回环）。 */
    fun importAnniversaryJson(context: Context, json: String) {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return
        val editor = anniversaryPrefs(context).edit()
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
        anniversaryPrefs(context).getString(KEY_PUSH_PREFIX + kind, "") != today

    fun markPushed(context: Context, kind: String, today: String) {
        anniversaryPrefs(context).edit().putString(KEY_PUSH_PREFIX + kind, today).apply()
    }

    /** 距离下个 milestone 还有几天（>=1） */
    fun daysToNextMilestone(days: Int): Int {
        val next = MILESTONES.firstOrNull { it > days }
        if (next != null) return next - days
        val nextThousand = ((days / 1000) + 1) * 1000
        return nextThousand - days
    }

    private fun anniversaryPrefs(context: Context) =
        EdenStorage.prefs(context, ANNIVERSARY_PREFS_NAME)

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
