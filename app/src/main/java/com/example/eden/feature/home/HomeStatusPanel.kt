package com.example.eden.feature.home


import com.example.eden.R
import com.example.eden.core.storage.EdenStorage
import com.example.eden.core.util.FestivalCalendar
import com.example.eden.data.repository.ChatRepository
import com.example.eden.domain.character.MikuPersona
import com.example.eden.domain.economy.EconomyManager
import com.example.eden.feature.anniversary.AnniversaryDialog
import com.example.eden.feature.company.CompanyTimeTracker
import com.example.eden.feature.daily.DailyLoopManager
import com.example.eden.feature.daily.LoopStep
import com.example.eden.feature.signin.DailySignDialog
import com.example.eden.feature.signin.SignCalendarDialog
import com.example.eden.feature.signin.SignInRepository
import com.example.eden.feature.story.MilestoneRepository
import com.example.eden.ui.theme.edenColor
import android.view.View
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun MainActivity.refreshAffectionAndCompany() {
    val affection = EconomyManager.getAffection(this)
    // 好感度不设上限，直接展示数值
    tvAffinity.text = "$affection"

    // 余额入口：只显示「円」数额，图标是礼物
    tvYen.text = "${EconomyManager.getBalance(this)} 円"

    // 有新剧情（跨过里程碑但还没看）时点亮小红点提示
    btnAffectionEntry.visibility =
        if (MilestoneRepository.hasUnviewed(this)) View.VISIBLE else View.GONE

    // 陪伴时长统一由 CompanyTimeTracker 管理（秒级，聊天页每 30 秒 flush 一次）
    val seconds = CompanyTimeTracker.getTodaySeconds(this)
    tvOnlineTime.text = "今日陪伴：${CompanyTimeTracker.format(seconds)}"
}

internal fun MainActivity.refreshSignInStatus() {
    if (SignInRepository.isTodaySigned(this)) {
        tvSignInText.text = "已签到"
        tvSignInText.setTextColor(edenColor(R.attr.edenColorTextSecondary, R.color.eden_text_secondary))
        // 已签到即视为每日任务「签到」一项达成（幂等，跨天自动重置）
        if (DailyLoopManager.markDone(this, LoopStep.SIGN_IN)) refreshDailyTask()
    } else {
        tvSignInText.text = getString(R.string.main_sign_in)
        tvSignInText.setTextColor(edenColor(R.attr.edenColorAccent, R.color.eden_accent))
    }
}

internal fun MainActivity.showSignCalendarDialog() {
    val dialog = SignCalendarDialog(this)
    dialog.show()
    dialog.setOnDismissListener {
        refreshSignInStatus()
        refreshDailyTask()
        // 签到完成后（或用户主动关掉后）按最新进度给下一次指引
        maybeGuideLoop()
    }
}

/**
 * 进主页自动弹签到窗：当天未签到且今天还没弹过时触发。
 *
 * 每天只弹一次（`daily_sign_shown` 记 yyyy-MM-dd），用户点「取消」后当天也不再打扰，
 * 之后仍可从签到卡片进入日历页手动签到。
 *
 * @return 是否真的弹出了弹窗（用于与里程碑提示、每日自言自语互斥）
 */
internal fun MainActivity.maybeShowSignInDialog(): Boolean {
    if (SignInRepository.isTodaySigned(this)) return false
    val prefs = EdenStorage.prefs(this, "daily_sign_shown")
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    if (prefs.getString("shown_date", "") == today) return false
    if (isFinishing || isDestroyed) return false

    prefs.edit().putString("shown_date", today).apply()
    DailySignDialog(this) {
        // 签到成功后同步主页文案与每日任务进度
        refreshSignInStatus()
        refreshDailyTask()
    }.show()
    return true
}

// ==================== 纪念日 ====================

/**
 * 刷新签到下方纪念日卡片文本。首次聊天时间从 Room 异步取，避免在主线程开 DB。
 * 命中 milestone / 生日 / 节日当天用粉色 + ♡ 高亮，平日灰色。
 */
internal fun MainActivity.refreshAnniversaryStatus() {
    lifecycleScope.launch {
        val firstChatTime = ChatRepository(this@refreshAnniversaryStatus).firstChatTime(MikuPersona.ID)
        val days = DailyLoopManager.daysSinceStart(this@refreshAnniversaryStatus, firstChatTime)
        val cal = java.util.Calendar.getInstance()
        val isHighlight = DailyLoopManager.isMilestoneDay(days) ||
            DailyLoopManager.isBirthdayToday(this@refreshAnniversaryStatus, cal) ||
            FestivalCalendar.todayFestival(cal) != null
        tvAnniversaryText.text = when {
            days == null -> "还没开始"
            isHighlight -> "$days 天 ♡"
            else -> "$days 天"
        }
        tvAnniversaryText.setTextColor(
            if (isHighlight) edenColor(R.attr.edenColorAccent, R.color.eden_accent)
            else edenColor(R.attr.edenColorTextSecondary, R.color.eden_text_secondary)
        )
    }
}

/**
 * 点击纪念日卡片弹出详情。复用 [refreshAnniversaryStatus] 里取过的天数会过期，
 * 这里重新拉一次，保证对话框里展示的是最新数据。
 */
internal fun MainActivity.showAnniversaryDialog() {
    lifecycleScope.launch {
        val firstChatTime = ChatRepository(this@showAnniversaryDialog).firstChatTime(MikuPersona.ID)
        val days = DailyLoopManager.daysSinceStart(this@showAnniversaryDialog, firstChatTime)
        AnniversaryDialog(this@showAnniversaryDialog, days) { refreshAnniversaryStatus() }.show()
    }
}
