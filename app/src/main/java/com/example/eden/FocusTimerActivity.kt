package com.example.eden

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.eden.data.FocusRepository
import com.example.eden.data.FocusTask
import kotlinx.coroutines.launch

/**
 * 专注陪伴（番茄钟）全屏页。
 *
 * 三大块：
 *  - 计时：圆环 + 模式切换（专注 / 小憩 / 长休息），计时逻辑全部委托 [FocusTimerManager]；
 *  - 任务：番茄 todo 式的任务清单，选中后完成专注自动 +1 枚番茄；
 *  - 统计：今日 / 累计的番茄数与专注分钟。
 *
 * 专注时长会经 [FocusTimerManager] 写回 [CompanyTimeManager]，与聊天时长共同构成「今日陪伴」。
 */
class FocusTimerActivity : AppCompatActivity(), FocusTimerManager.Listener {

    private lateinit var btnBack: ImageView
    private lateinit var tvCompany: TextView
    private lateinit var llModes: LinearLayout
    private lateinit var ring: FocusRingView
    private lateinit var tvStatus: TextView
    private lateinit var tvTaskHint: TextView
    private lateinit var btnStartPause: Button
    private lateinit var btnGiveUp: Button
    private lateinit var btnAddTask: TextView
    private lateinit var tvTaskEmpty: TextView
    private lateinit var llTasks: LinearLayout
    private lateinit var tvTodayStats: TextView
    private lateinit var tvTotalStats: TextView

    private val repository by lazy { FocusRepository(applicationContext) }

    private val modes = FocusTimerManager.FocusMode.values().toList()
    private val modeChips = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_focus_timer)

        initViews()
        setupModeChips()
        setupListeners()
        refreshStats()
        lifecycleScope.launch { loadTasks() }
    }

    override fun onStart() {
        super.onStart()
        FocusTimerManager.attach(this, this)
    }

    override fun onStop() {
        super.onStop()
        FocusTimerManager.detach()
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btn_back)
        tvCompany = findViewById(R.id.tv_company)
        llModes = findViewById(R.id.ll_modes)
        ring = findViewById(R.id.focus_ring_view)
        tvStatus = findViewById(R.id.tv_status)
        tvTaskHint = findViewById(R.id.tv_task_hint)
        btnStartPause = findViewById(R.id.btn_start_pause)
        btnGiveUp = findViewById(R.id.btn_give_up)
        btnAddTask = findViewById(R.id.btn_add_task)
        tvTaskEmpty = findViewById(R.id.tv_task_empty)
        llTasks = findViewById(R.id.ll_tasks)
        tvTodayStats = findViewById(R.id.tv_today_stats)
        tvTotalStats = findViewById(R.id.tv_total_stats)
    }

    // ==================== 模式 chip ====================

    private fun setupModeChips() {
        val unselected = edenColor(R.attr.edenColorIcon, R.color.eden_icon)
        val selected = edenColor(R.attr.edenColorOnPrimary, R.color.eden_on_primary)
        modes.forEachIndexed { index, mode ->
            val chip = TextView(this).apply {
                text = "${mode.title} ${mode.seconds / 60}'"
                textSize = 13f
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setTextColor(unselected)
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = dp(8)
                }
            }
            chip.setOnClickListener {
                if (FocusTimerManager.snapshot(this).state != FocusTimerManager.TimerState.IDLE) {
                    toast("计时进行中，先暂停再切换模式")
                    return@setOnClickListener
                }
                FocusTimerManager.switchMode(this, mode)
            }
            modeChips.add(chip)
            llModes.addView(chip)
        }
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }
        btnStartPause.setOnClickListener { onPrimaryClicked() }
        btnGiveUp.setOnClickListener { onGiveUpClicked() }
        btnAddTask.setOnClickListener { showAddTaskDialog() }
    }

    private fun onPrimaryClicked() {
        val snap = FocusTimerManager.snapshot(this)
        when (snap.state) {
            FocusTimerManager.TimerState.IDLE ->
                FocusTimerManager.start(this, snap.mode, snap.taskId, snap.taskTitle)
            FocusTimerManager.TimerState.RUNNING -> FocusTimerManager.pause(this)
            FocusTimerManager.TimerState.PAUSED -> FocusTimerManager.resume(this)
        }
    }

    private fun onGiveUpClicked() {
        ThemedDialog.show(
            context = this,
            title = "放弃本次计时？",
            message = "已经专注的时间会保留在今日陪伴里。",
            positiveText = getString(R.string.focus_abandon),
            negativeText = "继续专注",
            iconRes = R.drawable.ic_timer
        ) {
            FocusTimerManager.stop(this)
        }
    }

    // ==================== 计时渲染（Listener 回调） ====================

    override fun onTick(snapshot: FocusTimerManager.Snapshot) {
        render(snapshot)
    }

    override fun onSegmentComplete(finishedMode: FocusTimerManager.FocusMode) {
        toast(
            if (finishedMode == FocusTimerManager.FocusMode.FOCUS) "一段专注完成啦，休息一下～"
            else "休息结束，继续加油！"
        )
        lifecycleScope.launch { loadTasks() }
        refreshStats()
    }

    private fun render(snapshot: FocusTimerManager.Snapshot) {
        val remainingSec = (snapshot.remainingMs + 999) / 1000
        ring.setTimeText(String.format("%02d:%02d", remainingSec / 60, remainingSec % 60))
        ring.setProgress(snapshot.progress)
        ring.setLabel(snapshot.mode.title)

        val isFocus = snapshot.mode == FocusTimerManager.FocusMode.FOCUS
        val statusText = when (snapshot.state) {
            FocusTimerManager.TimerState.IDLE -> getString(R.string.focus_ready)
            FocusTimerManager.TimerState.PAUSED -> "已暂停"
            FocusTimerManager.TimerState.RUNNING ->
                if (isFocus) "专注中…" else "休息中…"
        }
        tvStatus.text = statusText

        tvTaskHint.text = snapshot.taskTitle.ifBlank { getString(R.string.focus_no_task) }

        val running = snapshot.state != FocusTimerManager.TimerState.IDLE
        btnGiveUp.visibility = if (running) View.VISIBLE else View.GONE
        btnStartPause.text = when (snapshot.state) {
            FocusTimerManager.TimerState.IDLE -> if (isFocus) getString(R.string.focus_start) else "开始${snapshot.mode.title}"
            FocusTimerManager.TimerState.RUNNING -> "暂停"
            FocusTimerManager.TimerState.PAUSED -> "继续"
        }
        // 未开始才可以切换模式：进入计时后禁用 chip 切换
        modeChips.forEachIndexed { i, chip ->
            val selected = modes[i] == snapshot.mode
            chip.isSelected = selected
            chip.setTextColor(
                if (selected) edenColor(R.attr.edenColorOnPrimary, R.color.eden_on_primary)
                else edenColor(R.attr.edenColorIcon, R.color.eden_icon)
            )
            chip.isEnabled = !running
            chip.alpha = if (running) 0.5f else 1f
        }

        refreshCompanyTime()
    }

    private fun refreshCompanyTime() {
        val seconds = CompanyTimeManager.getTodaySeconds(this)
        tvCompany.text = "今日陪伴 ${CompanyTimeManager.format(seconds)}"
    }

    // ==================== 任务 ====================

    private suspend fun loadTasks() {
        val tasks = repository.activeTasks()
        val currentTaskId = FocusTimerManager.snapshot(this).taskId

        llTasks.removeAllViews()
        tvTaskEmpty.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE

        tasks.forEach { task ->
            val item = LayoutInflater.from(this).inflate(R.layout.item_focus_task, llTasks, false)
            item.findViewById<TextView>(R.id.tv_task_title).text = task.title
            item.findViewById<TextView>(R.id.tv_task_progress).text =
                "${task.donePomodoros} / ${task.targetPomodoros} 番茄"
            val dot = item.findViewById<ImageView>(R.id.iv_task_dot)
            val title = item.findViewById<TextView>(R.id.tv_task_title)
            val selected = task.id == currentTaskId
            dot.visibility = if (selected) View.VISIBLE else View.INVISIBLE
            title.setTextColor(
                if (selected) edenColor(R.attr.edenColorPrimary, R.color.eden_primary)
                else edenColor(R.attr.edenColorTextPrimary, R.color.eden_text_primary)
            )

            item.findViewById<View>(R.id.iv_task_delete).setOnClickListener {
                confirmDeleteTask(task)
            }
            item.setOnClickListener { selectTask(task) }
            llTasks.addView(item)
        }
    }

    private fun selectTask(task: FocusTask) {
        if (FocusTimerManager.snapshot(this).state != FocusTimerManager.TimerState.IDLE) {
            toast("计时进行中，先放弃或等这段结束再切换任务")
            return
        }
        FocusTimerManager.selectTask(this, task.id, task.title)
        lifecycleScope.launch { loadTasks() }
    }

    private fun confirmDeleteTask(task: FocusTask) {
        ThemedDialog.show(
            context = this,
            title = "删除任务？",
            message = "「${task.title}」的记录会一并移除。",
            positiveText = getString(R.string.message_options_delete),
            negativeText = getString(R.string.common_cancel),
            iconRes = R.drawable.ic_delete
        ) {
            lifecycleScope.launch {
                repository.archiveTask(task.id)
                if (FocusTimerManager.snapshot(this@FocusTimerActivity).taskId == task.id) {
                    FocusTimerManager.selectTask(this@FocusTimerActivity, 0L, "")
                }
                loadTasks()
            }
        }
    }

    private fun showAddTaskDialog() {
        val dialog = Dialog(this)
        val view = View.inflate(this, R.layout.dialog_focus_task, null)
        dialog.setContentView(view)

        val etTitle = view.findViewById<EditText>(R.id.et_task_title)
        val etTarget = view.findViewById<EditText>(R.id.et_task_target)

        view.findViewById<TextView>(R.id.dialog_task_cancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<TextView>(R.id.dialog_task_confirm).setOnClickListener {
            val title = etTitle.text.toString().trim()
            if (title.isEmpty()) {
                toast("给这次专注起个名字吧")
                return@setOnClickListener
            }
            val target = etTarget.text.toString().trim().toIntOrNull()?.coerceIn(1, 99) ?: 1
            dialog.dismiss()
            lifecycleScope.launch {
                repository.addTask(title, target)
                loadTasks()
            }
        }

        dialog.show()
        dialog.window?.let {
            it.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val lp = it.attributes
            lp.width = (resources.displayMetrics.widthPixels * 0.86).toInt()
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            it.attributes = lp
        }
    }

    // ==================== 统计 ====================

    private fun refreshStats() {
        lifecycleScope.launch {
            val stats = repository.stats()
            tvTodayStats.text = "今日：${stats.todayCount} 个番茄 · ${stats.todaySeconds / 60} 分钟"
            tvTotalStats.text = "累计：${stats.totalCount} 个番茄 · ${stats.totalSeconds / 60} 分钟"
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
