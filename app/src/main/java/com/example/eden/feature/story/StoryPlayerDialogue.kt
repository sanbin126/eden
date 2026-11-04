package com.example.eden.feature.story

import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.example.eden.R
import com.example.eden.data.repository.ProfileRepository
import com.example.eden.domain.character.StoryChoice
import com.example.eden.domain.character.StoryStep
import com.example.eden.domain.economy.EconomyManager
import kotlin.math.abs

/**
 * 剧情播放器的台词 / 打字机 / 选项卡 / 自动播放演出。
 *
 * 从 StoryPlayerActivity 原样拆出：仅以扩展函数承载，演出状态仍保存在 Activity 上，
 * 逻辑、文案、调用顺序与拆出前一致。
 */

// ==================== 台词 / 打字机 ====================

internal fun StoryPlayerActivity.showLine(step: StoryStep?, text: String) {
    cancelTypewriter()
    cancelAuto()
    tvDialogNext.visibility = View.INVISIBLE

    when (step) {
        is StoryStep.Say -> if (step.who == StoryPlayerActivity.YOU) {
            // 玩家台词：不显示角色立绘，名字牌用全名（未设置则用小名）
            tvDialogName.visibility = View.VISIBLE
            tvDialogName.text = playerName()
            showPlayerPortrait()
        } else {
            tvDialogName.visibility = View.VISIBLE
            tvDialogName.text = chapter?.casts?.get(step.who)?.name ?: step.who
            showPortrait(step.who, step.face)
        }
        is StoryStep.Narrate -> {
            // 旁白不显示名字牌；只有「单独描写某个角色」的旁白才挂该角色立绘，
            // 环境描写、多人动作等一律隐藏立绘，避免立绘一直挂着。
            tvDialogName.visibility = View.GONE
            val focus = step.focus
            if (focus != null) showPortrait(focus, null) else ivStoryPortrait.visibility = View.GONE
        }
        else -> tvDialogName.visibility = View.GONE
    }
    typeText(text)
}

private fun StoryPlayerActivity.typeText(text: String) {
    fullText = text
    tvDialogText.text = ""
    if (text.isEmpty()) {
        onTypingDone()
        return
    }
    typing = true
    var shown = 0
    val runnable = object : Runnable {
        override fun run() {
            shown++
            tvDialogText.text = text.substring(0, shown)
            if (shown < text.length) {
                ui.postDelayed(this, StoryPlayerActivity.TYPE_INTERVAL_MS)
            } else {
                onTypingDone()
            }
        }
    }
    typeRunnable = runnable
    ui.postDelayed(runnable, StoryPlayerActivity.TYPE_INTERVAL_MS)
}

private fun StoryPlayerActivity.onTypingDone() {
    typing = false
    typeRunnable = null
    tvDialogNext.visibility = View.VISIBLE
    scheduleAuto()
}

internal fun StoryPlayerActivity.completeTyping() {
    cancelTypewriter()
    tvDialogText.text = fullText
    typing = false
    tvDialogNext.visibility = View.VISIBLE
    scheduleAuto()
}

private fun StoryPlayerActivity.cancelTypewriter() {
    typeRunnable?.let { ui.removeCallbacks(it) }
    typeRunnable = null
}

// ==================== 选项卡 ====================

internal fun StoryPlayerActivity.showChoices(options: List<StoryChoice>, sceneId: String, choiceIndex: Int) {
    cancelAuto()
    cancelTypewriter()
    tvDialogNext.visibility = View.INVISIBLE
    choiceActive = true

    // 重温时「上次的选择」的下标；非重温为 -1（不锁）
    val lockedIndex = if (replayMode) {
        StoryProgressRepository.rememberedChoice(this, lineId, chapterIndex, sceneId, choiceIndex) ?: 0
    } else {
        -1
    }

    llChoices.removeAllViews()
    val inflater = LayoutInflater.from(this)
    options.forEachIndexed { index, option ->
        val item = inflater.inflate(R.layout.item_story_choice, llChoices, false) as TextView
        item.text = resolve(option.text)
        if (replayMode) {
            // 重温：选项不可再选，默认选中上次的选择，稍后自动沿它继续
            item.isClickable = false
            item.isSelected = index == lockedIndex
            item.alpha = if (index == lockedIndex) 1f else 0.45f
        } else {
            item.setOnClickListener {
                // 首次点击选中，再次点击确认
                if (item.isSelected) {
                    confirmChoice(option, sceneId, choiceIndex, index)
                } else {
                    selectOnly(item)
                }
            }
        }
        llChoices.addView(item)
    }
    llChoices.visibility = View.VISIBLE

    if (replayMode) {
        val pick = options.getOrNull(lockedIndex)
        if (pick != null) {
            ui.postDelayed({ confirmChoice(pick, sceneId, choiceIndex, lockedIndex) }, StoryPlayerActivity.REPLAY_CHOICE_DELAY_MS)
        }
    }
}

private fun StoryPlayerActivity.selectOnly(target: View) {
    for (i in 0 until llChoices.childCount) {
        llChoices.getChildAt(i).isSelected = false
    }
    target.isSelected = true
}

private fun StoryPlayerActivity.confirmChoice(option: StoryChoice, sceneId: String, choiceIndex: Int, optionIndex: Int) {
    choiceActive = false
    hideChoices()

    if (!sideMode) {
        if (!replayMode) {
            // 记下这次选择（供以后重温默认选中），并结算全局好感
            StoryProgressRepository.rememberChoice(this, lineId, chapterIndex, sceneId, choiceIndex, optionIndex)
            applyAffection(option.affection)
        }
        // 本章好感度单独累计，供章末判定使用（重温也累计，便于解锁另一条支线）
        chapterAffection = StoryProgressRepository.addChapterAffection(this, lineId, chapterIndex, option.affection)
    }

    val target = option.goto?.let { chapter?.sceneById(it) }
    if (target != null) enterScene(target) else nextStep()
}

internal fun StoryPlayerActivity.hideChoices() {
    llChoices.visibility = View.GONE
    llChoices.removeAllViews()
}

private fun StoryPlayerActivity.applyAffection(delta: Int) {
    if (delta == 0) return
    if (delta > 0) {
        EconomyManager.increase(this, delta)
    } else {
        EconomyManager.decrease(this, -delta)
    }
    val sign = if (delta > 0) "+" else "-"
    Toast.makeText(this, getString(R.string.story_affection_toast, sign, abs(delta)), Toast.LENGTH_SHORT).show()
}

// ==================== 自动 / 结束 ====================

internal fun StoryPlayerActivity.toggleAuto() {
    autoMode = !autoMode
    btnStoryAuto.alpha = if (autoMode) 1f else 0.55f
    if (autoMode) {
        if (!typing && !choiceActive && !ending) scheduleAuto()
    } else {
        cancelAuto()
    }
}

private fun StoryPlayerActivity.scheduleAuto() {
    if (!autoMode || choiceActive || ending) return
    cancelAuto()
    val runnable = Runnable { nextStep() }
    autoRunnable = runnable
    ui.postDelayed(runnable, StoryPlayerActivity.AUTO_DELAY_MS)
}

private fun StoryPlayerActivity.cancelAuto() {
    autoRunnable?.let { ui.removeCallbacks(it) }
    autoRunnable = null
}

internal fun StoryPlayerActivity.showEnding() {
    cancelTypewriter()
    cancelAuto()
    choiceActive = false
    ending = true
    fullText = getString(if (sideMode) R.string.story_side_end else R.string.story_chapter_end)
    tvDialogName.visibility = View.GONE
    tvDialogText.text = fullText
    tvDialogNext.visibility = View.VISIBLE
    typing = false
}

// ==================== 玩家名 / 占位符 ====================

/** 玩家角色名：优先全名，未设置则退回小名。 */
private fun StoryPlayerActivity.playerName(): String =
    ProfileRepository.getFormal(this).ifEmpty { ProfileRepository.getCall(this) }

/** 剧本占位符替换：{meFull} → 全名，{me} → 小名。 */
internal fun StoryPlayerActivity.resolve(text: String): String =
    text.replace("{meFull}", playerName()).replace("{me}", ProfileRepository.getCall(this))
