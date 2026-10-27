package com.example.eden.feature.focus


import com.example.eden.core.storage.EdenStorage
import com.example.eden.domain.economy.EconomyManager
import com.example.eden.feature.company.CompanyTimeTracker
import com.example.eden.feature.daily.DailyLoopManager
import com.example.eden.feature.daily.LoopStep
import com.example.eden.feature.greeting.GreetingNotifier
import com.example.eden.feature.story.MilestoneRepository
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.eden.data.repository.FocusRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 专注陪伴（番茄钟）的核心状态机。
 *
 * 设计要点：
 * 1. **不靠累减**：RUNNING 时只存「结束时间戳 endAt」，每次的剩余时间 = endAt - now，
 *    因此息屏、切后台、甚至 Activity 被回收都不会走慢或漂移。
 * 2. **写回陪伴**：专注（FOCUS）段每 30 秒把增量秒数交给 [CompanyTimeTracker]，
 *    与聊天页 flush 的口径一致；专注时长和聊天时长是两笔独立账，但都汇总进「今日陪伴」。
 * 3. **可靠性**：状态持久化在 SharedPreferences，进程被杀后再次进入 [reconcileIfExpired]
 *    会把「已经跑完」的专注段补记一次。
 * 4. **奖励**：一段专注完成 → 记一条 [com.example.eden.data.local.FocusSessionEntity]、
 *    给关联任务 +1 枚番茄、[EconomyManager] +1 好感，并推送完成通知；随后自动进入休息。
 */
object FocusTimerController {

    /** 番茄钟的三种模式 */
    enum class FocusMode(val seconds: Int, val title: String) {
        FOCUS(25 * 60, "专注"),
        SHORT(5 * 60, "小憩"),
        LONG(15 * 60, "长休息");
    }

    enum class TimerState { IDLE, RUNNING, PAUSED }

    /** 供界面渲染的只读快照 */
    data class Snapshot(
        val state: TimerState,
        val mode: FocusMode,
        val remainingMs: Long,
        val totalMs: Long,
        val taskId: Long,
        val taskTitle: String
    ) {
        /** 已完成比例 0f~1f */
        val progress: Float
            get() = if (totalMs <= 0L) 0f
            else ((totalMs - remainingMs).toFloat() / totalMs).coerceIn(0f, 1f)
    }

    interface Listener {
        fun onTick(snapshot: Snapshot)
        fun onSegmentComplete(finishedMode: FocusMode)
    }

    private const val PREFS = "focus_prefs"
    private const val KEY_STATE = "state"
    private const val KEY_MODE = "mode"
    private const val KEY_END_AT = "end_at"
    private const val KEY_REMAINING = "remaining_ms"
    private const val KEY_TOTAL = "total_ms"
    private const val KEY_TASK_ID = "task_id"
    private const val KEY_TASK_TITLE = "task_title"
    private const val KEY_CYCLE = "cycle_done"
    private const val KEY_LAST_FLUSH = "last_flush"

    private const val TICK_MS = 500L
    private const val FLUSH_INTERVAL_MS = 30_000L

    /** 每完成 4 段专注安排一次长休息 */
    private const val ROUNDS_BEFORE_LONG = 4

    /** 每完成一段专注产出的「円」与每日上限 */
    private const val FOCUS_YEN = 10
    private const val FOCUS_YEN_DAILY_CAP = 60

    /** 专注好感被刻意降权：每完成一段 +1，但每天最多 FOCUS_AFFECTION_DAILY_CAP 点 */
    private const val FOCUS_AFFECTION_DAILY_CAP = 4

    private const val COUNTER_FOCUS_YEN = "focus_yen"
    private const val COUNTER_FOCUS_AFFECTION = "focus_aff"

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var listener: Listener? = null
    private var repository: FocusRepository? = null
    private var appContext: Context? = null

    private val ticker = object : Runnable {
        override fun run() {
            val ctx = appContext ?: return
            val snap = snapshot(ctx)
            if (snap.state != TimerState.RUNNING) return
            if (snap.remainingMs <= 0L) {
                finishSegment(ctx, snap.mode)
                return
            }
            maybeFlush(ctx)
            listener?.onTick(snap)
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun prefs(context: Context) =
        EdenStorage.prefs(context, PREFS)

    private fun repository(context: Context): FocusRepository =
        repository ?: FocusRepository(context.applicationContext).also { repository = it }

    // ==================== 供界面调用 ====================

    /** Activity onStart 时绑定：刷新一次界面，并在需要时恢复计时。 */
    fun attach(context: Context, listener: Listener) {
        appContext = context.applicationContext
        this.listener = listener
        reconcileIfExpired(context)
        val snap = snapshot(context)
        listener.onTick(snap)
        if (snap.state == TimerState.RUNNING) startTicker()
    }

    /** Activity onStop 时解绑。计时任务在后台继续跑，以便到点仍能弹通知。 */
    fun detach() {
        listener = null
    }

    /** 读取当前状态快照（任何线程可调用，基于持久化数据计算）。 */
    fun snapshot(context: Context): Snapshot {
        val p = prefs(context)
        val state = runCatching {
            TimerState.valueOf(p.getString(KEY_STATE, TimerState.IDLE.name)!!)
        }.getOrDefault(TimerState.IDLE)
        val mode = runCatching {
            FocusMode.valueOf(p.getString(KEY_MODE, FocusMode.FOCUS.name)!!)
        }.getOrDefault(FocusMode.FOCUS)
        val total = p.getLong(KEY_TOTAL, mode.seconds * 1000L)
        val remaining = when (state) {
            TimerState.RUNNING ->
                (p.getLong(KEY_END_AT, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)
            TimerState.PAUSED -> p.getLong(KEY_REMAINING, total)
            TimerState.IDLE -> total
        }
        return Snapshot(
            state = state,
            mode = mode,
            remainingMs = remaining,
            totalMs = total,
            taskId = p.getLong(KEY_TASK_ID, 0L),
            taskTitle = p.getString(KEY_TASK_TITLE, "").orEmpty()
        )
    }

    /** 开始一段计时（先设置模式与任务）。 */
    fun start(context: Context, mode: FocusMode, taskId: Long, taskTitle: String) {
        appContext = context.applicationContext
        beginSegment(context, mode, taskId, taskTitle)
        emit(context)
    }

    /** 暂停：把剩余时间落盘，停掉心跳。 */
    fun pause(context: Context) {
        val p = prefs(context)
        if (readState(context) != TimerState.RUNNING) return
        flush(context)
        val remaining = (p.getLong(KEY_END_AT, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)
        p.edit()
            .putString(KEY_STATE, TimerState.PAUSED.name)
            .putLong(KEY_REMAINING, remaining)
            .apply()
        stopTicker()
        emit(context)
    }

    /** 继续：以剩余时间为准重设 endAt。 */
    fun resume(context: Context) {
        val p = prefs(context)
        if (readState(context) != TimerState.PAUSED) return
        val remaining = p.getLong(KEY_REMAINING, 0L)
        val now = System.currentTimeMillis()
        p.edit()
            .putString(KEY_STATE, TimerState.RUNNING.name)
            .putLong(KEY_END_AT, now + remaining)
            .putLong(KEY_LAST_FLUSH, now)
            .apply()
        startTicker()
        emit(context)
    }

    /** 放弃当前计时，回到「专注」待开始状态（已 flush 的陪伴时长予以保留）。 */
    fun stop(context: Context) {
        flush(context)
        prefs(context).edit()
            .putString(KEY_STATE, TimerState.IDLE.name)
            .putString(KEY_MODE, FocusMode.FOCUS.name)
            .putLong(KEY_TOTAL, FocusMode.FOCUS.seconds * 1000L)
            .putLong(KEY_REMAINING, FocusMode.FOCUS.seconds * 1000L)
            .putLong(KEY_LAST_FLUSH, 0L)
            .apply()
        stopTicker()
        emit(context)
    }

    /** 待开始状态下切换模式（专注 / 小憩 / 长休息）。 */
    fun switchMode(context: Context, mode: FocusMode) {
        if (readState(context) != TimerState.IDLE) return
        prefs(context).edit()
            .putString(KEY_MODE, mode.name)
            .putLong(KEY_TOTAL, mode.seconds * 1000L)
            .putLong(KEY_REMAINING, mode.seconds * 1000L)
            .apply()
        emit(context)
    }

    /** 待开始状态下更换关联任务。 */
    fun selectTask(context: Context, taskId: Long, taskTitle: String) {
        if (readState(context) != TimerState.IDLE) return
        prefs(context).edit()
            .putLong(KEY_TASK_ID, taskId)
            .putString(KEY_TASK_TITLE, taskTitle)
            .apply()
        emit(context)
    }

    // ==================== 内部 ====================

    private fun readState(context: Context): TimerState = runCatching {
        TimerState.valueOf(prefs(context).getString(KEY_STATE, TimerState.IDLE.name)!!)
    }.getOrDefault(TimerState.IDLE)

    private fun readMode(context: Context): FocusMode = runCatching {
        FocusMode.valueOf(prefs(context).getString(KEY_MODE, FocusMode.FOCUS.name)!!)
    }.getOrDefault(FocusMode.FOCUS)

    private fun beginSegment(context: Context, mode: FocusMode, taskId: Long, taskTitle: String) {
        appContext = context.applicationContext
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putString(KEY_STATE, TimerState.RUNNING.name)
            .putString(KEY_MODE, mode.name)
            .putLong(KEY_TOTAL, mode.seconds * 1000L)
            .putLong(KEY_END_AT, now + mode.seconds * 1000L)
            .putLong(KEY_TASK_ID, taskId)
            .putString(KEY_TASK_TITLE, taskTitle)
            .putLong(KEY_LAST_FLUSH, now)
            .apply()
        startTicker()
    }

    /** 一段计时走到 0 时的收尾：记奖励 / 通知 / 自动切换下一段。 */
    private fun finishSegment(context: Context, mode: FocusMode) {
        flush(context)
        stopTicker()
        val p = prefs(context)
        val taskId = p.getLong(KEY_TASK_ID, 0L)
        val taskTitle = p.getString(KEY_TASK_TITLE, "").orEmpty()

        if (mode == FocusMode.FOCUS) {
            scope.launch { repository(context).completeFocus(taskId, taskTitle, mode.seconds) }
            // 专注产出「円」（每日封顶）
            EconomyManager.earn(context, COUNTER_FOCUS_YEN, FOCUS_YEN, FOCUS_YEN_DAILY_CAP)
            // 专注加好感刻意降权：每天最多 FOCUS_AFFECTION_DAILY_CAP 点，跨里程碑时登记解锁
            if (EconomyManager.tryDailyGrant(context, COUNTER_FOCUS_AFFECTION, FOCUS_AFFECTION_DAILY_CAP) == 1) {
                val (newAffection, crossedMilestone) = EconomyManager.increase(context, 1)
                if (crossedMilestone) MilestoneRepository.onCrossed(context, newAffection)
            }
            FocusNotifier.notifyFocusComplete(context, taskTitle)

            // 每日循环：专注完成算一步，并让角色主动在聊天里说一句 → 把用户引回聊天
            DailyLoopManager.markDone(context, LoopStep.FOCUS)
            scope.launch { GreetingNotifier.notifyFocusDone(context) }

            // 每 4 段专注安排一次长休息（cycle 保存 0..3 的余数）
            val cycle = (p.getInt(KEY_CYCLE, 0) + 1) % ROUNDS_BEFORE_LONG
            p.edit().putInt(KEY_CYCLE, cycle).apply()
            val next = if (cycle == 0) FocusMode.LONG else FocusMode.SHORT
            beginSegment(context, next, taskId, taskTitle)
        } else {
            FocusNotifier.notifyBreakComplete(context)
            p.edit()
                .putString(KEY_STATE, TimerState.IDLE.name)
                .putString(KEY_MODE, FocusMode.FOCUS.name)
                .putLong(KEY_TOTAL, FocusMode.FOCUS.seconds * 1000L)
                .putLong(KEY_REMAINING, FocusMode.FOCUS.seconds * 1000L)
                .putLong(KEY_LAST_FLUSH, 0L)
                .apply()
        }
        listener?.onSegmentComplete(mode)
        emit(context)
    }

    /** 进程死亡后重新进入：把「已经跑完」的专注段补记一次。 */
    private fun reconcileIfExpired(context: Context) {
        val snap = snapshot(context)
        if (snap.state == TimerState.RUNNING && snap.remainingMs <= 0L) {
            finishSegment(context, snap.mode)
        }
    }

    /** 每 30 秒把专注增量写回「今日陪伴」。 */
    private fun maybeFlush(context: Context) {
        val p = prefs(context)
        val last = p.getLong(KEY_LAST_FLUSH, 0L)
        val now = System.currentTimeMillis()
        if (last <= 0L) {
            p.edit().putLong(KEY_LAST_FLUSH, now).apply()
            return
        }
        if (now - last >= FLUSH_INTERVAL_MS) {
            addElapsed(context, last, now)
            p.edit().putLong(KEY_LAST_FLUSH, now).apply()
        }
    }

    /** 立即结算一次（暂停 / 放弃 / 段结束前调用）。 */
    private fun flush(context: Context) {
        val p = prefs(context)
        val last = p.getLong(KEY_LAST_FLUSH, 0L)
        if (last <= 0L) return
        addElapsed(context, last, System.currentTimeMillis())
        p.edit().putLong(KEY_LAST_FLUSH, System.currentTimeMillis()).apply()
    }

    /** 把 [from, to] 之间的整秒数累加进陪伴时长（仅 FOCUS 段计入）。 */
    private fun addElapsed(context: Context, from: Long, to: Long) {
        if (readMode(context) != FocusMode.FOCUS) return
        val seconds = ((to - from) / 1000).toInt()
        CompanyTimeTracker.addSeconds(context, seconds)
    }

    private fun startTicker() {
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun stopTicker() {
        handler.removeCallbacks(ticker)
    }

    private fun emit(context: Context) {
        listener?.onTick(snapshot(context))
    }
}
