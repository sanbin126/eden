package com.example.eden.feature.signin


import com.example.eden.core.session.SessionManager
import com.example.eden.core.storage.EdenStorage
import com.example.eden.domain.economy.EconomyManager
import android.content.Context
import com.example.eden.core.session.SyncKeys
import com.example.eden.domain.character.MikuPersona
import com.example.eden.domain.world.WorldEvent
import com.example.eden.domain.world.WorldStateStore
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*

object SignInRepository {
    private const val PREFS_NAME = "sign_in_prefs"
    private const val KEY_SIGNED_DATES = "signed_dates"
    /** 暖心话「洗牌轮转」队列：存尚未用过的索引，逗号分隔 */
    private const val KEY_SIGN_LINE_QUEUE = "sign_line_queue"
    /** 上一句用过的索引，用于避免两轮交界处连续两天撞同一句 */
    private const val KEY_SIGN_LINE_LAST = "sign_line_last"

    /** 签到基础奖励（円） */
    private const val SIGN_IN_YEN_BASE = 20
    /** 连续签到每天追加的奖励（円） */
    private const val SIGN_IN_YEN_PER_DAY = 5
    /** 签到单次奖励封顶（円） */
    private const val SIGN_IN_YEN_MAX = 40

    /**
     * 获取已签到日期集合
     */
    fun getSignedDates(context: Context): Set<String> {
        val prefs = EdenStorage.prefs(context, PREFS_NAME)
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
        EdenStorage.prefs(context, PREFS_NAME)
            .edit()
            .putStringSet(KEY_SIGNED_DATES, signedDates)
            .apply()
        SessionManager.notifyChanged(context, SyncKeys.SIGN_IN)

        // 签到发放「円」：基础 20，连续签到每天 +5，封顶 40。
        // 上面已用「今天是否签过」去重，这里不会重复发币。
        val streak = getContinuousDays(context)
        val reward = (SIGN_IN_YEN_BASE + (streak - 1) * SIGN_IN_YEN_PER_DAY)
            .coerceAtMost(SIGN_IN_YEN_MAX)
        EconomyManager.grant(context, reward)
        // 今日新签到：给情绪一点正向反馈（重复签到在前面已提前返回）
        WorldStateStore.applyEvent(context, event = WorldEvent.SignedIn)

        return streak
    }

    // ==================== 与后端的业务数据同步（导出 / 导入） ====================

    /** 导出全部签到日期为 JSON 数组字符串（供上传后端）。 */
    fun exportJson(context: Context): String {
        val arr = JSONArray()
        getSignedDates(context).sorted().forEach { arr.put(it) }
        return arr.toString()
    }

    /**
     * 把后端拉取到的 JSON 合并进本地签到记录（不做上传回调，避免回环）。
     *
     * 签到是「只增不减」的数据，这里取本地与远端的并集而非整体覆盖：
     * 一旦某天签到没能上传成功（远端缺这一天），覆盖式导入会把本地那天抹掉，
     * 表现为「明明签过却显示没签」。
     *
     * @return true 表示本地存在远端没有的日期，调用方应把合并结果回传后端补齐。
     */
    fun importJson(context: Context, json: String): Boolean {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return false
        val remote = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            arr.optString(i).takeIf { it.isNotBlank() }?.let { remote.add(it) }
        }
        val local = getSignedDates(context)
        val merged = local + remote
        if (merged != local) {
            EdenStorage.prefs(context, PREFS_NAME)
                .edit()
                .putStringSet(KEY_SIGNED_DATES, merged)
                .apply()
        }
        return (local - remote).isNotEmpty()
    }

    /**
     * 取一句当日签到的暖心话，保证池子走完一轮前不重复。
     *
     * 维护一个打乱后的索引队列，每次取队首；取空后重新洗牌排队，并把上一轮最后
     * 用过的索引挪到队尾，避免两轮交界处连续两天撞同一句。
     * 池子 ≥ 8 条，因此一周之内不会重样。
     */
    fun nextSignInLine(context: Context): String {
        val lines = MikuPersona.DAILY_SIGN_LINES
        if (lines.isEmpty()) return ""

        val prefs = EdenStorage.prefs(context, PREFS_NAME)
        val queue = prefs.getString(KEY_SIGN_LINE_QUEUE, "")
            .orEmpty()
            .split(",")
            .mapNotNull { it.toIntOrNull() }
            .toMutableList()

        if (queue.isEmpty()) {
            val refill = lines.indices.shuffled().toMutableList()
            val last = prefs.getInt(KEY_SIGN_LINE_LAST, -1)
            if (refill.size > 1 && refill.first() == last) {
                val first = refill.first()
                refill[0] = refill.last()
                refill[refill.size - 1] = first
            }
            queue.addAll(refill)
        }

        val index = queue.removeAt(0)
        prefs.edit()
            .putString(KEY_SIGN_LINE_QUEUE, queue.joinToString(","))
            .putInt(KEY_SIGN_LINE_LAST, index)
            .apply()
        return lines.getOrElse(index) { lines.first() }
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