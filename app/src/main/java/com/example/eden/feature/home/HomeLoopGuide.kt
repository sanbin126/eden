package com.example.eden.feature.home


import com.example.eden.core.storage.EdenStorage
import com.example.eden.domain.character.MikuPersona
import com.example.eden.feature.daily.DailyLoopManager
import com.example.eden.feature.daily.LoopStep
import com.example.eden.feature.signin.SignInRepository
import com.example.eden.feature.story.MilestoneRepository
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random

// ==================== 每日随机小剧场 ====================
/**
 * 进 App 概率触发三玖「自言自语」两三句，给用户「她有自己的生活」的感觉。
 *
 * 触发条件（同时满足）：
 * 1. 今天已签到——未签到时让位给签到弹窗，签到完下次进 App 才有机会遇到；
 * 2. 今天还没触发过小剧场（用 SharedPreferences 记 yyyy-MM-dd）；
 * 3. 60% 概率命中——未命中不写日期，当天再次进 App 仍有机会，避免「错过今天就没了」的挫败。
 *
 * 命中后逐句延时显示在角色头顶，每句间隔 3s（一句显示 2.5s + 0.3s 渐隐刚好衔接）。
 * 每段 2-3 句，台词池见 [com.example.eden.domain.character.MikuPersona.DAILY_SOLILOQUIES]。
 *
 * @return 是否真的触发了自言自语（用于和「里程碑提示」互斥，避免两句台词抢戏）。
 */
internal fun MainActivity.maybeShowDailySoliloquy(): Boolean {
    if (!SignInRepository.isTodaySigned(this)) return false
    val prefs = EdenStorage.prefs(this, "daily_soliloquy_shown")
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    if (prefs.getString("shown_date", "") == today) return false
    if (Random.nextInt(100) >= 60) return false
    // ivCharacter 未测量好时（onResume 极早调用）跳过本次，下次进 App 再说
    // 注意：必须在写日期之前 return，否则今天的机会会被「吞掉」
    if (ivCharacter.width == 0 || ivCharacter.height == 0) return false

    prefs.edit().putString("shown_date", today).apply()

    val soliloquy = MikuPersona.DAILY_SOLILOQUIES.random()
    soliloquy.forEachIndexed { index, line ->
        ivCharacter.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            showHeadBubble(line)
        }, index * 3000L)
    }
    return true
}

/**
 * 好感度跨过里程碑后进主页时，让三玖做一次「有话想说」的即时提示 + 开心弹跳，
 * 把玩家注意力引到好感度旁的红点上。仅在本次进程内触发一次，避免每次回主页都打断。
 * 立绘尚未测量好时跳过，等下次进主页再补。
 *
 * @return 是否真的给出了提示（供主页气泡互斥判断）。
 */
internal fun MainActivity.maybeNudgeMilestone(): Boolean {
    if (milestoneNudged) return false
    if (!MilestoneRepository.hasUnviewed(this)) return false
    if (ivCharacter.width == 0 || ivCharacter.height == 0) return false
    milestoneNudged = true
    ivCharacter.post {
        if (isFinishing || isDestroyed) return@post
        motion.happyBounce()
        showHeadBubble("……那个，等你忙完，我有点话想跟你说。")
    }
    return true
}

/**
 * 回到主页时的任务兜底指引：找出今天第一项还没完成的任务，用一句台词 + 脉冲高亮
 * 把用户引过去。同一项任务当天只指引一次（跨天由 [DailyLoopManager] 自动重置），
 * 避免每次回主页都重复弹同一句。
 *
 * @return 是否真的给出了指引（供主页气泡互斥判断）。
 */
internal fun MainActivity.maybeGuideLoop(): Boolean {
    val next = DailyLoopManager.nextStep(this) ?: return false
    if (DailyLoopManager.lastGuided(this) == next.id) return false
    if (ivCharacter.width == 0 || ivCharacter.height == 0) return false
    ivCharacter.post {
        if (isFinishing || isDestroyed) return@post
        guideToStep(next)
    }
    return true
}

/** 就地指引：一句引导台词 + 目标入口脉冲高亮，并记下「已指引过该节点」。 */
private fun MainActivity.guideToStep(step: LoopStep) {
    val line = MikuPersona.LOOP_GUIDE_LINES[step.id]?.random() ?: return
    DailyLoopManager.markGuided(this, step)
    showHeadBubble(line)
    highlightLoopAnchor(step)
}

/**
 * 对某项任务对应的主页入口做 alpha 脉冲高亮。
 * 立绘的 scale / rotation / translation / colorFilter 已被 CharacterMotion 独占，
 * 只有 alpha 空闲，因此统一只用 alpha 脉冲，避免属性互相抢。
 */
private fun MainActivity.highlightLoopAnchor(step: LoopStep) {
    val anchor = when (step) {
        LoopStep.SIGN_IN -> cardSignIn
        LoopStep.TOUCH -> ivCharacter
        LoopStep.GAME -> btnGame
        LoopStep.CHAT -> btnStartChat
        LoopStep.FOCUS -> btnTimer
        LoopStep.FEED -> btnGiftEntry
    }
    cancelLoopHighlight()
    val original = anchor.alpha
    loopHighlight = ValueAnimator.ofFloat(original, 0.4f, original, 0.4f, original).apply {
        duration = 1600L
        addUpdateListener { anchor.alpha = it.animatedValue as Float }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                anchor.alpha = original
                loopHighlight = null
            }
        })
        start()
    }
}

internal fun MainActivity.cancelLoopHighlight() {
    loopHighlight?.cancel()
    loopHighlight = null
}
