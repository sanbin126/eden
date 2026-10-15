package com.example.eden

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

/**
 * 每日任务进度记录（本机当日状态，不参与 [SyncManager] 同步）。
 *
 * 记住三件事：今天哪些条目已完成、「全部完成」奖励是否已领取，以及主页最近一次
 * 已经给过指引的条目（用于去重，避免回到主页就反复弹同一句引导）。
 * 周期按自然日 `yyyy-MM-dd` 划分，跨天自动重来。
 */
object DailyLoopManager {
    private const val PREFS_NAME = "daily_loop_prefs"
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
        EdenStorage.prefs(context, PREFS_NAME)
}
