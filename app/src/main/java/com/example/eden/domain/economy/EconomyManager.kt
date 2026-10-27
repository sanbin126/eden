package com.example.eden.domain.economy

import android.content.Context
import android.content.SharedPreferences
import com.example.eden.core.session.SyncKeys
import com.example.eden.core.session.SessionManager
import com.example.eden.core.storage.EdenStorage
import com.example.eden.domain.model.GiftItem
import com.example.eden.domain.model.GiftKind
import com.example.eden.domain.world.WorldEvent
import com.example.eden.domain.world.WorldStateStore
import com.example.eden.feature.story.MilestoneRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * 角色养成经济的唯一入口：好感度、音符余额与每日/每周计数、投喂礼物。
 *
 * 原先的 EconomyManager / EconomyManager / EconomyManager 三个 object 合并于此，
 * 因为它们共享同一份「养女儿」经济状态，拆开只会让调用方跨三个类来回跳。
 */
object EconomyManager {

    // ==================== 来自 EconomyManager ====================
    private const val AFFECTION_PREFS_NAME = "affection_prefs"
    private const val KEY_AFFECTION = "affection_value"

    /**
     * 获取当前好感度
     */
    fun getAffection(context: Context): Int {
        val affectionPrefs = getPrefs(context)
        return affectionPrefs.getInt(KEY_AFFECTION, 0)
    }

    /**
     * 增加好感度，返回 Pair(新值, 是否刚突破10的倍数)。
     * 好感度不设上限，可持续累积，每跨过 10 的倍数解锁一次里程碑。
     */
    fun increase(context: Context, amount: Int = 1): Pair<Int, Boolean> {
        val current = getAffection(context)
        val newValue = (current + amount).coerceAtLeast(0)
        getPrefs(context).edit().putInt(KEY_AFFECTION, newValue).apply()
        SessionManager.notifyChanged(context, SyncKeys.AFFECTION)

        val crossedMilestone = (current / 10) < (newValue / 10)
        return Pair(newValue, crossedMilestone)
    }

    /**
     * 减少好感度（保留备用）
     */
    fun decrease(context: Context, amount: Int = 1): Int {
        val current = getAffection(context)
        val newValue = (current - amount).coerceAtLeast(0)
        getPrefs(context).edit().putInt(KEY_AFFECTION, newValue).apply()
        SessionManager.notifyChanged(context, SyncKeys.AFFECTION)
        return newValue
    }

    // ==================== 与后端的业务数据同步（导出 / 导入） ====================

    /** 导出好感度为 JSON 对象字符串（供上传后端）。 */
    fun exportAffectionJson(context: Context): String =
        JSONObject().put(KEY_AFFECTION, getAffection(context)).toString()

    /** 用后端拉取到的 JSON 覆盖本地好感度（不做上传回调，避免回环）。 */
    fun importAffectionJson(context: Context, json: String) {
        val value = runCatching { JSONObject(json).optInt(KEY_AFFECTION, 0) }.getOrDefault(0)
        getPrefs(context).edit()
            .putInt(KEY_AFFECTION, value.coerceAtLeast(0))
            .apply()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return EdenStorage.prefs(context, AFFECTION_PREFS_NAME)
    }

/**
 * 货币「円」（yen）钱包 + 每日 / 每周计数器。
 *
 * - 余额 [getBalance] 参与 [SessionManager] 同步（LWW）；本地变更后回调 notifyChanged，
 *   让「三玖的零花钱」在多设备间尽量保持一致。
 * - 每日 / 每周计数器（各类产出的当日配额、投喂的日/周限次）属于「本机当日状态」，
 *   不参与同步，照搬 [MilestoneRepository] 的本地-only 先例，避免多设备互相拉扯。
 *
 * 计数器集中存成两段 JSON（day / week），读取时若发现周期已翻篇，自动视为全新周期，
 * 省去逐 key 清理的麻烦。
 */

    // ==================== 来自 EconomyManager ====================
    private const val WALLET_PREFS_NAME = "wallet_prefs"
    private const val KEY_BALANCE = "yen_balance"

    /** 每日计数器 JSON：{"period":"yyyy-MM-dd","values":{...}} */
    private const val KEY_DAY = "counters_day"

    /** 每周计数器 JSON：{"period":"yyyy-ww","values":{...}} */
    private const val KEY_WEEK = "counters_week"

    // ==================== 余额 ====================

    fun getBalance(context: Context): Int = walletPrefs(context).getInt(KEY_BALANCE, 0)

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
        walletPrefs(context).edit().putInt(KEY_BALANCE, value.coerceAtLeast(0)).apply()
        SessionManager.notifyChanged(context, SyncKeys.WALLET)
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
    fun exportWalletJson(context: Context): String =
        JSONObject().put(KEY_BALANCE, getBalance(context)).toString()

    /** 用后端拉取到的 JSON 覆盖本地余额（不做上传回调，避免回环）。 */
    fun importWalletJson(context: Context, json: String) {
        val value = runCatching { JSONObject(json).optInt(KEY_BALANCE, 0) }.getOrDefault(0)
        walletPrefs(context).edit().putInt(KEY_BALANCE, value.coerceAtLeast(0)).apply()
    }

    // ==================== 内部实现 ====================

    /** 读取计数器容器；周期不同则返回一个空容器（下次写入时自然落盘）。 */
    private fun counters(context: Context, prefsKey: String, period: String): JSONObject {
        val obj = runCatching { JSONObject(walletPrefs(context).getString(prefsKey, "") ?: "") }
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
        walletPrefs(context).edit().putString(prefsKey, obj.toString()).apply()
        return newValue
    }

    private fun todayStr(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    private fun weekStr(): String =
        SimpleDateFormat("yyyy-ww", Locale.getDefault()).format(Date())

    private fun walletPrefs(context: Context): SharedPreferences =
        EdenStorage.prefs(context, WALLET_PREFS_NAME)

/**
 * 投喂 / 送礼的结算逻辑：扣款 → 记限次 → 加好感 → 返回三玖的专属回应。
 *
 * 限次（日 / 周）与余额的扣减顺序为「先校验、后扣款」：任一项不满足则整体不生效，
 * 避免出现「扣了钱却没送到」的情况。好感度跨过里程碑时同步登记解锁（红点 + 后续 AI 提示）。
 */

    // ==================== 来自 EconomyManager ====================
    /** 一次投喂的结果，供 UI 决定弹气泡还是提示不足。 */
    sealed class FeedResult {
        data class Success(
            val item: GiftItem,
            val line: String,
            val newAffection: Int,
            val crossedMilestone: Boolean
        ) : FeedResult()

        /** 达到日 / 周限次 */
        data class LimitReached(val item: GiftItem) : FeedResult()

        /** 円不足 */
        data class InsufficientYen(val item: GiftItem) : FeedResult()
    }

    /**
     * 投喂一件物品。所有校验通过后才真正扣款并结算好感。
     */
    fun feed(context: Context, item: GiftItem): FeedResult {
        if (isLimitReached(context, item)) return FeedResult.LimitReached(item)
        if (!EconomyManager.canAfford(context, item.cost)) return FeedResult.InsufficientYen(item)
        if (!EconomyManager.spend(context, item.cost)) return FeedResult.InsufficientYen(item)

        if (item.dailyLimit > 0) EconomyManager.addDaily(context, dailyKey(item), 1)
        if (item.weeklyLimit > 0) EconomyManager.addWeekly(context, weeklyKey(item), 1)

        val (newAffection, crossedMilestone) = EconomyManager.increase(context, item.affection)
        if (crossedMilestone) MilestoneRepository.onCrossed(context, newAffection)

        // 情绪结算：礼物类比饮料/食物更「用心」，开心程度更高
        WorldStateStore.applyEvent(context, event = WorldEvent.FedGift(item.kind == GiftKind.GIFT))

        return FeedResult.Success(item, item.lines.random(), newAffection, crossedMilestone)
    }

    /** 该物品当前是否已达限次。 */
    fun isLimitReached(context: Context, item: GiftItem): Boolean =
        (item.dailyLimit > 0 && EconomyManager.dailyValue(context, dailyKey(item)) >= item.dailyLimit) ||
            (item.weeklyLimit > 0 && EconomyManager.weeklyValue(context, weeklyKey(item)) >= item.weeklyLimit)

    /** 限次剩余文案，如「今日还可 2 次」/「本周还可 1 次」/「不限量」。 */
    fun remainingLabel(context: Context, item: GiftItem): String = when {
        item.dailyLimit > 0 -> {
            val left = (item.dailyLimit - EconomyManager.dailyValue(context, dailyKey(item))).coerceAtLeast(0)
            "今日还可 $left 次"
        }
        item.weeklyLimit > 0 -> {
            val left = (item.weeklyLimit - EconomyManager.weeklyValue(context, weeklyKey(item))).coerceAtLeast(0)
            "本周还可 $left 次"
        }
        else -> "不限量"
    }

    private fun dailyKey(item: GiftItem) = "feed_${item.id}"

    private fun weeklyKey(item: GiftItem) = "feedw_${item.id}"
}
