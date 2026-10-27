package com.example.eden.feature.story


import com.example.eden.core.storage.EdenStorage
import com.example.eden.domain.economy.EconomyManager
import android.content.Context
import android.content.SharedPreferences
import com.example.eden.domain.character.StoryChapter

/**
 * 剧情板块的进度管理。
 *
 * ## 隔离
 * 进度按**线路**（[lineId]，即 [com.example.eden.domain.character.Characters.current] 的 id）分开存储：
 * prefs 基础名为 `story_prefs_<lineId>`，再由 [EdenStorage.prefs] 拼上账号分区后缀。
 * 这样换角色后，新线路从第一章开始，旧线路进度原样保留，互不影响。
 *
 * 只落本地 SharedPreferences，不参与后端同步（与 [MilestoneRepository] 一致）。
 */
object StoryProgressRepository {

    private const val KEY_DONE = "done"
    private const val KEY_LAST_READ = "last_read"
    private const val KEY_SIDE_UNLOCKED = "side_unlocked"

    /** 章节内的续读点：[sceneId] 场景中第 [stepIndex] 步（下次要演出的那一步）。 */
    data class ResumePoint(val sceneId: String, val stepIndex: Int)

    private fun resumeKey(chapterIndex: Int): String = "resume_$chapterIndex"

    private fun chapterAffectionKey(chapterIndex: Int): String = "chapter_affection_$chapterIndex"

    private fun sideKey(chapterIndex: Int, sideId: String): String = "$chapterIndex:$sideId"

    private fun choiceKey(chapterIndex: Int, sceneId: String, stepIndex: Int): String =
        "choice_${chapterIndex}_${sceneId}_$stepIndex"

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

    // ==================== 章节内续读点 ====================

    /**
     * 读取章节内的续读点；没有记录返回 null。
     * 「继续」与「重新开始」两种入口由它区分。
     */
    fun resumePoint(context: Context, lineId: String, chapterIndex: Int): ResumePoint? {
        val raw = getPrefs(context, lineId).getString(resumeKey(chapterIndex), null) ?: return null
        val sep = raw.indexOf('|')
        if (sep <= 0) return null
        val step = raw.substring(sep + 1).toIntOrNull() ?: return null
        return ResumePoint(raw.substring(0, sep), step)
    }

    /** 记录续读点：每推进一步调用一次，写入开销很小。 */
    fun setResume(context: Context, lineId: String, chapterIndex: Int, sceneId: String, stepIndex: Int) {
        getPrefs(context, lineId).edit()
            .putString(resumeKey(chapterIndex), "$sceneId|$stepIndex")
            .apply()
    }

    /** 清空续读点（「重新开始」时用）。 */
    fun clearResume(context: Context, lineId: String, chapterIndex: Int) {
        getPrefs(context, lineId).edit().remove(resumeKey(chapterIndex)).apply()
    }

    // ==================== 选择记忆（重温用） ====================

    /**
     * 记住一个选择点的选择结果，供「重温」时默认选中。
     *
     * @param stepIndex 该 [com.example.eden.domain.character.StoryStep.Choice] 在场景 steps 中的下标
     */
    fun rememberChoice(
        context: Context,
        lineId: String,
        chapterIndex: Int,
        sceneId: String,
        stepIndex: Int,
        optionIndex: Int
    ) {
        getPrefs(context, lineId).edit()
            .putInt(choiceKey(chapterIndex, sceneId, stepIndex), optionIndex)
            .apply()
    }

    /** 读取某个选择点上次选中的下标；没选过返回 null。 */
    fun rememberedChoice(
        context: Context,
        lineId: String,
        chapterIndex: Int,
        sceneId: String,
        stepIndex: Int
    ): Int? {
        val v = getPrefs(context, lineId).getInt(choiceKey(chapterIndex, sceneId, stepIndex), -1)
        return if (v >= 0) v else null
    }

    // ==================== 本章好感度（章末判定用） ====================

    /** 本章选项累计的好感度；未记录返回 0。 */
    fun chapterAffection(context: Context, lineId: String, chapterIndex: Int): Int =
        getPrefs(context, lineId).getInt(chapterAffectionKey(chapterIndex), 0)

    /** 累加本章好感度并返回新值。 */
    fun addChapterAffection(context: Context, lineId: String, chapterIndex: Int, delta: Int): Int {
        val next = chapterAffection(context, lineId, chapterIndex) + delta
        getPrefs(context, lineId).edit().putInt(chapterAffectionKey(chapterIndex), next).apply()
        return next
    }

    /** 重置本章好感度（重新开始本章时调用）。 */
    fun resetChapterAffection(context: Context, lineId: String, chapterIndex: Int) {
        getPrefs(context, lineId).edit().putInt(chapterAffectionKey(chapterIndex), 0).apply()
    }

    // ==================== 章末支线 ====================

    /** 记录一条支线已解锁（重复解锁无副作用）。 */
    fun unlockSideStory(context: Context, lineId: String, chapterIndex: Int, sideId: String) {
        val unlocked = sideUnlocked(context, lineId).toMutableSet()
        if (!unlocked.add(sideKey(chapterIndex, sideId))) return
        getPrefs(context, lineId).edit().putStringSet(KEY_SIDE_UNLOCKED, unlocked).apply()
    }

    fun isSideStoryUnlocked(context: Context, lineId: String, chapterIndex: Int, sideId: String): Boolean =
        sideUnlocked(context, lineId).contains(sideKey(chapterIndex, sideId))

    private fun sideUnlocked(context: Context, lineId: String): Set<String> =
        getPrefs(context, lineId).getStringSet(KEY_SIDE_UNLOCKED, emptySet()).orEmpty().toSet()

    // ==================== 解锁 ====================

    /**
     * 章节是否已解锁：前置章节通关 **且** 好感达到门槛。
     * 第一章（prevChapter <= 0）无前置要求。
     */
    fun isUnlocked(context: Context, lineId: String, chapter: StoryChapter): Boolean {
        val prevOk = chapter.prevChapter <= 0 || isCompleted(context, lineId, chapter.prevChapter)
        if (!prevOk) return false
        return EconomyManager.getAffection(context) >= chapter.requiredAffection
    }

    /** 未解锁时的原因文案；已解锁返回 null。 */
    fun lockReason(context: Context, lineId: String, chapter: StoryChapter): String? {
        if (isUnlocked(context, lineId, chapter)) return null
        if (chapter.prevChapter > 0 && !isCompleted(context, lineId, chapter.prevChapter)) {
            return "通关第 ${chapter.prevChapter} 章后解锁"
        }
        val lack = chapter.requiredAffection - EconomyManager.getAffection(context)
        return "好感达到 ${chapter.requiredAffection} 解锁（还差 $lack）"
    }
}
