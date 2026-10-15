package com.example.eden

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 每日任务页。
 *
 * 列出当天 6 项任务（各一句话描述）与完成状态；六项全部完成后，用户可在此手动领取 20 円。
 * 进度与领取状态由 [DailyLoopManager] 按自然日保存，跨天自动重置。
 */
class DailyTaskActivity : AppCompatActivity() {

    private lateinit var tvTaskProgress: TextView
    private lateinit var tvTaskHint: TextView
    private lateinit var tasksContainer: LinearLayout
    private lateinit var btnClaim: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_daily_task)

        tvTaskProgress = findViewById(R.id.tv_task_progress)
        tvTaskHint = findViewById(R.id.tv_task_hint)
        tasksContainer = findViewById(R.id.tasks_container)
        btnClaim = findViewById(R.id.btn_claim)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }
        btnClaim.setOnClickListener { claimReward() }

        render()
    }

    override fun onResume() {
        super.onResume()
        // 可能刚从聊天 / 游戏等页面回来，进度有变化，重新渲染
        render()
    }

    /** 依据当日进度重绘进度文案、任务清单与领取按钮三态。 */
    private fun render() {
        val (count, total) = DailyLoopManager.todayProgress(this)
        val reward = DailyLoopManager.ALL_DONE_REWARD_YEN
        val claimed = DailyLoopManager.isRewardClaimed(this)

        tvTaskProgress.text = getString(R.string.daily_task_progress, count, total)
        tvTaskHint.text = when {
            claimed -> getString(R.string.daily_task_hint_claimed)
            count >= total -> getString(R.string.daily_task_hint_ready)
            else -> getString(R.string.daily_task_hint_todo, total, reward)
        }

        renderTasks()

        when {
            claimed -> {
                btnClaim.text = getString(R.string.daily_task_claimed)
                setClaimEnabled(false)
            }
            count >= total -> {
                btnClaim.text = getString(R.string.daily_task_claim, reward)
                setClaimEnabled(true)
            }
            else -> {
                btnClaim.text = getString(R.string.daily_task_claim_locked, reward)
                setClaimEnabled(false)
            }
        }
    }

    /** 逐条列出 6 项任务，条目之间插一条细分隔线。 */
    private fun renderTasks() {
        tasksContainer.removeAllViews()
        val dividerColor = edenColor(R.attr.edenColorDividerAlt, R.color.eden_divider)
        LoopStep.values().forEachIndexed { index, step ->
            if (index > 0) {
                tasksContainer.addView(
                    View(this).apply { setBackgroundColor(dividerColor) },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)
                    ).apply { marginStart = dpToPx(52) }
                )
            }

            val row = layoutInflater.inflate(R.layout.item_daily_task, tasksContainer, false)
            val done = DailyLoopManager.isDone(this, step)
            val mark = row.findViewById<TextView>(R.id.task_mark)
            val title = row.findViewById<TextView>(R.id.task_title)
            val state = row.findViewById<TextView>(R.id.task_state)

            mark.text = if (done) "✓" else "○"
            mark.setTextColor(
                if (done) edenColor(R.attr.edenColorAccent, R.color.eden_accent)
                else edenColor(R.attr.edenColorTextHint, R.color.eden_text_tertiary)
            )
            title.text = step.title
            title.alpha = if (done) 0.55f else 1f
            state.text = getString(
                if (done) R.string.daily_task_state_done else R.string.daily_task_state_todo
            )
            state.setTextColor(
                if (done) edenColor(R.attr.edenColorAccent, R.color.eden_accent)
                else edenColor(R.attr.edenColorTextTertiary, R.color.eden_text_tertiary)
            )
            tasksContainer.addView(row)
        }
    }

    /** 手动领取「全部完成」奖励：先由 [DailyLoopManager] 抢占领取权（防重复），再发「円」。 */
    private fun claimReward() {
        if (!DailyLoopManager.claimReward(this)) return
        WalletManager.grant(this, DailyLoopManager.ALL_DONE_REWARD_YEN)
        render()
        Toast.makeText(
            this,
            getString(R.string.daily_task_claimed_toast, DailyLoopManager.ALL_DONE_REWARD_YEN),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun setClaimEnabled(enabled: Boolean) {
        btnClaim.isEnabled = enabled
        btnClaim.isClickable = enabled
        btnClaim.alpha = if (enabled) 1f else 0.5f
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt() + 1
}
