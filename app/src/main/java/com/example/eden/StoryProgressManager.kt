package com.example.eden

import android.content.Context
import android.content.SharedPreferences
import com.example.eden.character.StoryChapter

/**
 * 剧情板块的进度管理。
 *
 * ## 隔离
 * 进度按**线路**（[lineId]，即 [com.example.eden.character.Characters.current] 的 id）分开存储：
 * prefs 基础名为 `story_prefs_<lineId>`，再由 [EdenStorage.prefs] 拼上账号分区后缀。
 * 这样换角色后，新线路从第一章开始，旧线路进度原样保留，互不影响。
 *
 * 只落本地 SharedPreferences，不参与后端同步（与 [MilestoneManager] 一致）。
 */
object StoryProgressManager {

    private const val KEY_DONE = "done"
    private const val KEY_LAST_READ = "last_read"

    private fun prefsName(lineId: String): String = "story_prefs_$lineId"

    private fun getPrefs(context: Context, lineId: String): SharedPreferences =
        EdenStorage.prefs(context, prefsName(lineId))

    // ==================== 通关记录 ====================

    /** 已通关的章节序号集合。 */
    fun completedChapters(context: Context, lineId: String): Set<Int> =
        getPrefs(context, lineId).getStringSet(KEY_DONE, emptySet())
            .orEmpty()
            .mapNotNull { it.toIntOrNull() }
            .toSet()

    fun isCompleted(context: Context, lineId: String, chapterIndex: Int): Boolean =
        completedChapters(context, lineId).contains(chapterIndex)

    /** 标记某章通关；返回 true 表示这是首次通关（调用方据此发放好感奖励）。 */
    fun markCompleted(context: Context, lineId: String, chapterIndex: Int): Boolean {
        val done = completedChapters(context, lineId).toMutableSet()
        if (!done.add(chapterIndex)) return false
        getPrefs(context, lineId).edit()
            .putStringSet(KEY_DONE, done.map { it.toString() }.toSet())
            .apply()
        return true
    }

    // ==================== 续读位置 ====================

    /** 最近读到 / 打开的章节序号；无记录返回 0。 */
    fun lastReadChapter(context: Context, lineId: String): Int =
        getPrefs(context, lineId).getInt(KEY_LAST_READ, 0)

    fun setLastReadChapter(context: Context, lineId: String, chapterIndex: Int) {
        getPrefs(context, lineId).edit().putInt(KEY_LAST_READ, chapterIndex).apply()
    }

    // ==================== 解锁 ====================

    /**
     * 章节是否已解锁：前置章节通关 **且** 好感达到门槛。
     * 第一章（prevChapter <= 0）无前置要求。
     */
    fun isUnlocked(context: Context, lineId: String, chapter: StoryChapter): Boolean {
        val prevOk = chapter.prevChapter <= 0 || isCompleted(context, lineId, chapter.prevChapter)
        if (!prevOk) return false
        return AffectionManager.getAffection(context) >= chapter.requiredAffection
    }

    /** 未解锁时的原因文案；已解锁返回 null。 */
    fun lockReason(context: Context, lineId: String, chapter: StoryChapter): String? {
        if (isUnlocked(context, lineId, chapter)) return null
        if (chapter.prevChapter > 0 && !isCompleted(context, lineId, chapter.prevChapter)) {
            return "通关第 ${chapter.prevChapter} 章后解锁"
        }
        val lack = chapter.requiredAffection - AffectionManager.getAffection(context)
        return "好感达到 ${chapter.requiredAffection} 解锁（还差 $lack）"
    }
}
