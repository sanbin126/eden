package com.example.eden

import android.content.Context
import android.content.SharedPreferences
import com.example.eden.auth.SyncKeys
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 货币「円」（yen）钱包 + 每日 / 每周计数器。
 *
 * - 余额 [getBalance] 参与 [SyncManager] 同步（LWW）；本地变更后回调 notifyChanged，
 *   让「三玖的零花钱」在多设备间尽量保持一致。
 * - 每日 / 每周计数器（各类产出的当日配额、投喂的日/周限次）属于「本机当日状态」，
 *   不参与同步，照搬 [MilestoneManager] 的本地-only 先例，避免多设备互相拉扯。
 *
 * 计数器集中存成两段 JSON（day / week），读取时若发现周期已翻篇，自动视为全新周期，
 * 省去逐 key 清理的麻烦。
 */
object WalletManager {
    private const val PREFS_NAME = "wallet_prefs"
    private const val KEY_BALANCE = "yen_balance"

    /** 每日计数器 JSON：{"period":"yyyy-MM-dd","values":{...}} */
    private const val KEY_DAY = "counters_day"

    /** 每周计数器 JSON：{"period":"yyyy-ww","values":{...}} */
    private const val KEY_WEEK = "counters_week"

    // ==================== 余额 ====================

    fun getBalance(context: Context): Int = prefs(context).getInt(KEY_BALANCE, 0)

    fun canAfford(context: Context, cost: Int): Boolean = getBalance(context) >= cost

    /** 扣款；余额不足返回 false 且不做任何改动。 */
    fun spend(context: Context, cost: Int): Boolean {
        val current = getBalance(context)
        if (cost < 0 || cost > current) return false
        setBalance(context, current - cost)
        return true
    }

    /** 无条件加币（已自带去重的来源用，如签到），并触发同步。 */
    fun grant(context: Context, amount: Int): Int {
        if (amount <= 0) return getBalance(context)
        val newBalance = getBalance(context) + amount
        setBalance(context, newBalance)
        return newBalance
    }

    /**
     * 带每日上限的加币：本次实际入账 = min(amount, 上限 - 今日已领)。
     * 用于「聊天 / 触碰 / 专注 / 小游戏」等可重复触发、需要封顶的产出。
     *
     * @return 实际加入的金额（可能为 0，表示今日该来源已领满）。
     */
    fun earn(context: Context, source: String, amount: Int, dailyCap: Int): Int {
        if (amount <= 0) return 0
        val already = dailyValue(context, source)
        val allowed = (dailyCap - already).coerceAtLeast(0)
        val add = minOf(amount, allowed)
        if (add <= 0) return 0
        addDaily(context, source, add)
        setBalance(context, getBalance(context) + add)
        return add
    }

    private fun setBalance(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_BALANCE, value.coerceAtLeast(0)).apply()
        SyncManager.notifyChanged(context, SyncKeys.WALLET)
    }

    // ==================== 每日计数器 ====================

    /** 今日某计数器的值（周期已翻篇则为 0）。 */
    fun dailyValue(context: Context, key: String): Int =
        counters(context, KEY_DAY, todayStr()).optJSONObject("values")?.optInt(key, 0) ?: 0

    /** 累加今日计数器，返回累加后的值。 */
    fun addDaily(context: Context, key: String, delta: Int): Int =
        bump(context, KEY_DAY, todayStr(), key, delta)

    /** 若今日计数器未达上限则 +1 并返回 1，否则返回 0（用于好感度这类「每天最多 N 次」）。 */
    fun tryDailyGrant(context: Context, key: String, cap: Int): Int {
        if (dailyValue(context, key) >= cap) return 0
        addDaily(context, key, 1)
        return 1
    }

    // ==================== 每周计数器 ====================

    /** 本周某计数器的值（周期已翻篇则为 0）。 */
    fun weeklyValue(context: Context, key: String): Int =
        counters(context, KEY_WEEK, weekStr()).optJSONObject("values")?.optInt(key, 0) ?: 0

    /** 累加本周计数器，返回累加后的值。 */
    fun addWeekly(context: Context, key: String, delta: Int): Int =
        bump(context, KEY_WEEK, weekStr(), key, delta)

    /** 若本周计数器未达上限则 +1 并返回 1，否则返回 0。 */
    fun tryWeeklyGrant(context: Context, key: String, cap: Int): Int {
        if (weeklyValue(context, key) >= cap) return 0
        addWeekly(context, key, 1)
        return 1
    }

    // ==================== 与后端的业务数据同步（导出 / 导入） ====================

    /** 仅余额参与同步；计数器是本机当日状态，不入同步。 */
    fun exportJson(context: Context): String =
        JSONObject().put(KEY_BALANCE, getBalance(context)).toString()

    /** 用后端拉取到的 JSON 覆盖本地余额（不做上传回调，避免回环）。 */
    fun importJson(context: Context, json: String) {
        val value = runCatching { JSONObject(json).optInt(KEY_BALANCE, 0) }.getOrDefault(0)
        prefs(context).edit().putInt(KEY_BALANCE, value.coerceAtLeast(0)).apply()
    }

    // ==================== 内部实现 ====================

    /** 读取计数器容器；周期不同则返回一个空容器（下次写入时自然落盘）。 */
    private fun counters(context: Context, prefsKey: String, period: String): JSONObject {
        val obj = runCatching { JSONObject(prefs(context).getString(prefsKey, "") ?: "") }
            .getOrElse { JSONObject() }
        if (obj.optString("period") != period) {
            return JSONObject().put("period", period).put("values", JSONObject())
        }
        if (!obj.has("values")) obj.put("values", JSONObject())
        return obj
    }

    private fun bump(
        context: Context,
        prefsKey: String,
        period: String,
        key: String,
        delta: Int
    ): Int {
        val obj = counters(context, prefsKey, period)
        val values = obj.getJSONObject("values")
        val newValue = (values.optInt(key, 0) + delta).coerceAtLeast(0)
        values.put(key, newValue)
        prefs(context).edit().putString(prefsKey, obj.toString()).apply()
        return newValue
    }

    private fun todayStr(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    private fun weekStr(): String =
        SimpleDateFormat("yyyy-ww", Locale.getDefault()).format(Date())

    private fun prefs(context: Context): SharedPreferences =
        EdenStorage.prefs(context, PREFS_NAME)
}
