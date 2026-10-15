package com.example.eden

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.example.eden.character.MilestoneStories
import org.json.JSONArray
import org.json.JSONObject

/**
 * 好感度里程碑小剧场的状态管理。
 *
 * 只用 SharedPreferences（+ 少量 JSON）持久化，**不参与后端同步**——
 * 里程碑剧情属于本地演出记录，跨设备同步意义不大，也避免 SyncManager 需要新增 key 映射。
 *
 * 数据模型：
 * - [KEY_UNLOCKED]：已跨过且配置了剧情的里程碑（10/20…）
 * - [KEY_DONE]：已看完的里程碑
 * - 红点 = unlocked - done 非空；点进去后标记 done
 * - [KEY_RECORDS]：每个里程碑用户选了哪些选项，供回看时重放
 * - [KEY_AI_HINT]：一次性 AI 提示，记录"刚刚突破到的里程碑"，聊天时注入 system 后即清除
 */
object MilestoneManager {

    private const val PREFS_NAME = "milestone_prefs"
    private const val KEY_UNLOCKED = "unlocked"
    private const val KEY_DONE = "done"
    private const val KEY_RECORDS = "records"
    private const val KEY_AI_HINT = "ai_hint"
    private const val NO_HINT = -1

    // ==================== 解锁 / 红点 ====================

    /** 已解锁（跨过且有剧情）的里程碑集合。 */
    fun getUnlocked(context: Context): Set<Int> = getIntSet(context, KEY_UNLOCKED)

    /** 已看完的里程碑集合。 */
    fun getDone(context: Context): Set<Int> = getIntSet(context, KEY_DONE)

    /** 是否存在还没看过的小剧场（用于红点）。 */
    fun hasUnviewed(context: Context): Boolean = nextUnviewed(context) != null

    /** 最早一个尚未看过的里程碑；没有则返回 null。按顺序播放，保证剧情先后不乱。 */
    fun nextUnviewed(context: Context): Int? =
        (getUnlocked(context) - getDone(context)).minOrNull()

    /** 某个里程碑是否已看完。 */
    fun isCompleted(context: Context, milestone: Int): Boolean =
        getDone(context).contains(milestone)

    /**
     * 进入小剧场的统一入口：有新剧情进新剧情，没有则进列表页（可回看）。
     * 主界面红点、角色详情弹窗入口都走这里，保证两处行为一致。
     */
    fun openEntry(context: Context) {
        val next = nextUnviewed(context)
        val intent = if (next != null) {
            Intent(context, MilestoneStoryActivity::class.java)
                .putExtra(MilestoneStoryActivity.EXTRA_MILESTONE, next)
                .putExtra(MilestoneStoryActivity.EXTRA_REPLAY, false)
        } else {
            Intent(context, MilestoneStoryListActivity::class.java)
        }
        // 从非 Activity（如 Application context）启动时需带 NEW_TASK
        if (context !is android.app.Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * 好感度跨过 10 的倍数时登记解锁，并埋下一次性的 AI 提示。
     *
     * 只有配置了剧情的里程碑才登记（[MilestoneStories.forMilestone] 非空），
     * 未配置的倍数不会产生红点，避免"点了却什么都没有"。
     */
    fun onCrossed(context: Context, newValue: Int) {
        val milestone = (newValue / 10) * 10
        if (milestone <= 0) return
        if (MilestoneStories.forMilestone(milestone) == null) return

        val unlocked = getUnlocked(context).toMutableSet()
        if (unlocked.add(milestone)) {
            saveIntSet(context, KEY_UNLOCKED, unlocked)
        }
        // 突破瞬间埋一次提示，等下一次真实 AI 请求时注入 system
        getPrefs(context).edit().putInt(KEY_AI_HINT, milestone).apply()
    }

    /** 看完整段小剧情后标记完成，并记录用户的选择（供回看重放）。 */
    fun markCompleted(context: Context, milestone: Int, choices: List<String>) {
        val done = getDone(context).toMutableSet()
        if (done.add(milestone)) {
            saveIntSet(context, KEY_DONE, done)
        }
        saveRecord(context, milestone, choices)
    }

    // ==================== 历史记录 ====================

    /** 读取某个里程碑用户当时的选择文本列表（按轮次顺序）。 */
    fun recordOf(context: Context, milestone: Int): List<String> {
        val all = runCatching {
            JSONObject(getPrefs(context).getString(KEY_RECORDS, "{}") ?: "{}")
        }.getOrDefault(JSONObject())
        val arr = all.optJSONArray(milestone.toString()) ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    }

    // ==================== AI 关系升温提示 ====================

    /**
     * 取出并清除一次性的里程碑提示。
     * 返回非空表示"刚刚突破到该里程碑"，调用方应注入一条 system 让角色感知关系升温。
     * 用完即清，避免之后每轮请求都重复注入。
     */
    fun consumeAiHint(context: Context): Int? {
        val value = getPrefs(context).getInt(KEY_AI_HINT, NO_HINT)
        if (value <= 0) return null
        getPrefs(context).edit().putInt(KEY_AI_HINT, NO_HINT).apply()
        return value
    }

    // ==================== 内部工具 ====================

    private fun saveRecord(context: Context, milestone: Int, choices: List<String>) {
        val all = runCatching {
            JSONObject(getPrefs(context).getString(KEY_RECORDS, "{}") ?: "{}")
        }.getOrDefault(JSONObject())
        val arr = JSONArray()
        choices.forEach { arr.put(it) }
        all.put(milestone.toString(), arr)
        getPrefs(context).edit().putString(KEY_RECORDS, all.toString()).apply()
    }

    private fun getIntSet(context: Context, key: String): Set<Int> =
        getPrefs(context).getStringSet(key, emptySet())
            .orEmpty()
            .mapNotNull { it.toIntOrNull() }
            .toSet()

    private fun saveIntSet(context: Context, key: String, values: Set<Int>) {
        getPrefs(context).edit()
            .putStringSet(key, values.map { it.toString() }.toSet())
            .apply()
    }

    private fun getPrefs(context: Context): SharedPreferences =
        EdenStorage.prefs(context, PREFS_NAME)
}
