package com.example.eden

import android.content.Context

/**
 * 投喂 / 送礼的结算逻辑：扣款 → 记限次 → 加好感 → 返回三玖的专属回应。
 *
 * 限次（日 / 周）与余额的扣减顺序为「先校验、后扣款」：任一项不满足则整体不生效，
 * 避免出现「扣了钱却没送到」的情况。好感度跨过里程碑时同步登记解锁（红点 + 后续 AI 提示）。
 */
object GiftManager {

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
        if (!WalletManager.canAfford(context, item.cost)) return FeedResult.InsufficientYen(item)
        if (!WalletManager.spend(context, item.cost)) return FeedResult.InsufficientYen(item)

        if (item.dailyLimit > 0) WalletManager.addDaily(context, dailyKey(item), 1)
        if (item.weeklyLimit > 0) WalletManager.addWeekly(context, weeklyKey(item), 1)

        val (newAffection, crossedMilestone) = AffectionManager.increase(context, item.affection)
        if (crossedMilestone) MilestoneManager.onCrossed(context, newAffection)

        return FeedResult.Success(item, item.lines.random(), newAffection, crossedMilestone)
    }

    /** 该物品当前是否已达限次。 */
    fun isLimitReached(context: Context, item: GiftItem): Boolean =
        (item.dailyLimit > 0 && WalletManager.dailyValue(context, dailyKey(item)) >= item.dailyLimit) ||
            (item.weeklyLimit > 0 && WalletManager.weeklyValue(context, weeklyKey(item)) >= item.weeklyLimit)

    /** 限次剩余文案，如「今日还可 2 次」/「本周还可 1 次」/「不限量」。 */
    fun remainingLabel(context: Context, item: GiftItem): String = when {
        item.dailyLimit > 0 -> {
            val left = (item.dailyLimit - WalletManager.dailyValue(context, dailyKey(item))).coerceAtLeast(0)
            "今日还可 $left 次"
        }
        item.weeklyLimit > 0 -> {
            val left = (item.weeklyLimit - WalletManager.weeklyValue(context, weeklyKey(item))).coerceAtLeast(0)
            "本周还可 $left 次"
        }
        else -> "不限量"
    }

    private fun dailyKey(item: GiftItem) = "feed_${item.id}"

    private fun weeklyKey(item: GiftItem) = "feedw_${item.id}"
}
