package com.example.eden.feature.story


import com.example.eden.R
import com.example.eden.core.ui.BaseActivity
import com.example.eden.core.ui.ThemedDialog
import com.example.eden.domain.economy.EconomyManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.eden.domain.character.Characters
import com.example.eden.domain.character.StoryChapter
import com.example.eden.domain.character.StoryLibrary
import com.example.eden.domain.character.StoryScene
import com.example.eden.domain.character.StoryStep
import com.example.eden.domain.world.WorldEvent
import com.example.eden.domain.world.WorldStateStore

/**
 * 剧情演出播放器：galgame 式逐句推进。
 *
 * 演出流程：章节标题卡 → 逐场景逐句演出（打字机 + 立绘 + 渐变背景 → 选项卡 → 章节完）。
 *
 * ## 隔离
 * - 线路 [lineId] 决定剧本来源 `assets/story/<lineId>/` 与进度 `story_prefs_<lineId>`；
 * - 剧中说话人 [StoryStep.Say.who] 决定立绘来源 `assets/portrait/<castId>/`。
 * 两者都来自 Intent / 剧本，切角色时不会互相污染。
 *
 * ## 拆分
 * 台词 / 打字机 / 选项卡 / 自动播放见 StoryPlayerDialogue.kt，
 * 背景 / 立绘见 StoryPlayerVisuals.kt；演出状态仍集中在本类。
 */
class StoryPlayerActivity : BaseActivity() {

    companion object {
        const val EXTRA_LINE_ID = "extra_line_id"
        const val EXTRA_CHAPTER_INDEX = "extra_chapter_index"

        /** 章末支线（番外）剧本文件名，不含扩展名；传入后进入支线播放模式。 */
        const val EXTRA_SIDE_FILE = "extra_side_file"

        /** 打字机速度：每字间隔 */
        internal const val TYPE_INTERVAL_MS = 35L

        /** 自动播放时，一句读完后停留时长 */
        internal const val AUTO_DELAY_MS = 1600L

        /** 「重温」时，选项默认选中的展示时长，之后自动按上次的选择继续 */
        internal const val REPLAY_CHOICE_DELAY_MS = 1200L

        /** 立绘解码目标高度（px），足够清晰又省内存 */
        internal const val PORTRAIT_TARGET_PX = 1300

        /** 立绘默认显示高度（dp），与布局中的基准值一致 */
        internal const val PORTRAIT_HEIGHT_DEFAULT_DP = 360

        /**
         * 立绘显示高度覆盖（dp）。四叶的立绘是跳跃欢呼姿态（单臂高举、抬腿），
         * 身体在画布中的占比明显小于其他姐妹，同高度下视觉上会小一圈，故单独放大。
         */
        internal val PORTRAIT_HEIGHT_OVERRIDE_DP = mapOf("yotsuba" to 415)

        /** 玩家角色的说话人 id（剧本中用 you 表示） */
        internal const val YOU = "you"

        /** CG 插画在 assets 下的目录前缀；CG 自带人物，演出期间隐藏立绘 */
        internal const val CG_ROOT = "story_cg/"
    }

    /** 标题卡的进入形态：首次打开 / 中途续读 / 已通关重温。 */
    private enum class EntryMode { FIRST, RESUME, REPLAY }

    internal lateinit var lineId: String
    internal var chapterIndex: Int = 1
    internal var chapter: StoryChapter? = null

    /** 支线剧本文件名；非空时处于支线播放模式（不写主线进度、不结算好感）。 */
    private var sideFile: String? = null
    internal val sideMode: Boolean get() = sideFile != null

    internal lateinit var ivStoryBg: ImageView
    internal lateinit var ivStoryPortrait: ImageView
    internal lateinit var tvSceneLabel: TextView
    internal lateinit var llChoices: LinearLayout
    internal lateinit var tvDialogName: TextView
    internal lateinit var tvDialogText: TextView
    internal lateinit var tvDialogNext: TextView
    private lateinit var btnStoryBack: View
    internal lateinit var btnStoryAuto: TextView
    private lateinit var btnStorySkip: TextView
    private lateinit var cardTitle: View
    private lateinit var btnTitleStart: TextView
    private lateinit var btnTitleRestart: TextView

    // ==================== 演出状态 ====================
    internal val ui = Handler(Looper.getMainLooper())
    internal val portraitCache = mutableMapOf<String, Bitmap>()

    private var currentScene: StoryScene? = null
    private var stepIndex = 0

    internal var typing = false
    internal var fullText = ""
    internal var typeRunnable: Runnable? = null

    internal var autoMode = false
    internal var autoRunnable: Runnable? = null

    /** 选项卡展示中：此时点击舞台不推进 */
    internal var choiceActive = false

    /** 重温模式：选项不可再选，自动沿上次的选择继续，且不重复结算好感 */
    internal var replayMode = false

    /** 当前背景是否为 CG 插画：CG 内的三玖是全屏画面，此时不再叠加立绘 */
    internal var bgIsCg = false

    /** 已到「本章完」，再点一次才结算离开 */
    internal var ending = false

    private var finished = false

    /** 本章选项累计好感度（章末判定用，不含全局好感与通关奖励）。 */
    internal var chapterAffection = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_story_player)

        lineId = intent.getStringExtra(EXTRA_LINE_ID) ?: Characters.current.id
        sideFile = intent.getStringExtra(EXTRA_SIDE_FILE)
        chapterIndex = intent.getIntExtra(EXTRA_CHAPTER_INDEX, 1)
        chapter = sideFile?.let { StoryLibrary.loadSideStory(this, lineId, it) }
            ?: StoryLibrary.loadChapter(this, lineId, chapterIndex)
        val ch = chapter
        if (ch == null || ch.scenes.isEmpty()) {
            Toast.makeText(this, getString(R.string.story_home_empty), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        bindViews()
        if (sideMode) {
            bindSideTitleCard(ch)
        } else {
            bindTitleCard(ch)
            // 记录续读位置：首页「继续阅读」据此定位
            StoryProgressRepository.setLastReadChapter(this, lineId, chapterIndex)
        }
    }

    private fun bindViews() {
        ivStoryBg = findViewById(R.id.iv_story_bg)
        ivStoryPortrait = findViewById(R.id.iv_story_portrait)
        tvSceneLabel = findViewById(R.id.tv_scene_label)
        llChoices = findViewById(R.id.ll_choices)
        tvDialogName = findViewById(R.id.tv_dialog_name)
        tvDialogText = findViewById(R.id.tv_dialog_text)
        tvDialogNext = findViewById(R.id.tv_dialog_next)
        btnStoryBack = findViewById(R.id.btn_story_back)
        btnStoryAuto = findViewById(R.id.btn_story_auto)
        btnStorySkip = findViewById(R.id.btn_story_skip)
        cardTitle = findViewById(R.id.card_chapter_title)

        findViewById<View>(R.id.story_stage_root).setOnClickListener { onStageClick() }
        btnStoryBack.setOnClickListener { finish() }
        btnStoryAuto.setOnClickListener { toggleAuto() }
        btnStorySkip.setOnClickListener { finishChapter() }
    }

    private fun bindTitleCard(ch: StoryChapter) {
        cardTitle.findViewById<TextView>(R.id.tv_title_chip).text = ch.chapterLabel
        cardTitle.findViewById<TextView>(R.id.tv_title_label).text = "CHAPTER %02d".format(ch.index)
        cardTitle.findViewById<TextView>(R.id.tv_title_text).text = ch.title
        cardTitle.findViewById<TextView>(R.id.tv_title_synopsis).text = ch.synopsis
        btnTitleStart = cardTitle.findViewById(R.id.btn_title_start)
        btnTitleRestart = cardTitle.findViewById(R.id.btn_title_skip)
        applyEntryMode(ch)
    }

    /** 支线标题卡：番外没有主线的章节序号与进度概念，只保留「开始」。 */
    private fun bindSideTitleCard(ch: StoryChapter) {
        cardTitle.findViewById<TextView>(R.id.tv_title_chip).text = getString(R.string.story_side_chip)
        cardTitle.findViewById<TextView>(R.id.tv_title_label).text = "SIDE STORY"
        cardTitle.findViewById<TextView>(R.id.tv_title_text).text = ch.title
        cardTitle.findViewById<TextView>(R.id.tv_title_synopsis).text = ch.synopsis
        btnTitleStart = cardTitle.findViewById(R.id.btn_title_start)
        btnTitleRestart = cardTitle.findViewById(R.id.btn_title_skip)
        btnTitleStart.setText(R.string.story_start)
        btnTitleStart.setOnClickListener { startStory(resume = false, replay = false) }
        btnTitleRestart.visibility = View.GONE
    }

    /**
     * 标题卡按进度呈现三种形态：
     * - 首次打开（没推进过）：只显示「开始」；
     * - 中途退出过且有续读点：「继续」+「重新开始」；
     * - 已通关：「重温」（重温时选项不可再选，默认沿上次的选择走）。
     */
    private fun applyEntryMode(ch: StoryChapter) {
        val point = StoryProgressRepository.resumePoint(this, lineId, chapterIndex)
        val mode = when {
            StoryProgressRepository.isCompleted(this, lineId, chapterIndex) -> EntryMode.REPLAY
            point != null && ch.sceneById(point.sceneId) != null -> EntryMode.RESUME
            else -> EntryMode.FIRST
        }
        when (mode) {
            EntryMode.FIRST -> {
                btnTitleStart.setText(R.string.story_start)
                btnTitleStart.setOnClickListener { startStory(resume = false, replay = false) }
                btnTitleRestart.visibility = View.GONE
            }
            EntryMode.RESUME -> {
                btnTitleStart.setText(R.string.story_continue)
                btnTitleStart.setOnClickListener { startStory(resume = true, replay = false) }
                btnTitleRestart.visibility = View.VISIBLE
                btnTitleRestart.setText(R.string.story_restart)
                btnTitleRestart.setOnClickListener {
                    StoryProgressRepository.clearResume(this, lineId, chapterIndex)
                    startStory(resume = false, replay = false)
                }
            }
            EntryMode.REPLAY -> {
                btnTitleStart.setText(R.string.story_replay)
                btnTitleStart.setOnClickListener { startStory(resume = false, replay = true) }
                btnTitleRestart.visibility = View.GONE
            }
        }
    }

    private fun startStory(resume: Boolean, replay: Boolean) {
        val ch = chapter ?: return
        replayMode = replay
        if (!sideMode) {
            // 续读时恢复本章已累计的好感；从头开始（含重温）则清零重新累计
            chapterAffection = if (resume) {
                StoryProgressRepository.chapterAffection(this, lineId, chapterIndex)
            } else {
                StoryProgressRepository.resetChapterAffection(this, lineId, chapterIndex)
                0
            }
        }
        cardTitle.animate().alpha(0f).setDuration(280)
            .withEndAction { cardTitle.visibility = View.GONE }
            .start()

        // 续读点存在且能定位到场景时原样续演，否则从头开始
        val point = if (resume) StoryProgressRepository.resumePoint(this, lineId, chapterIndex) else null
        if (point != null && enterSceneAt(point)) return
        enterScene(ch.scenes.first())
    }

    // ==================== 场景 / 步骤推进 ====================

    internal fun enterScene(scene: StoryScene) {
        currentScene = scene
        stepIndex = 0
        applyBackground(scene.backgroundId)
        hideChoices()
        nextStep()
    }

    /**
     * 按续读点恢复演出：定位到记录的场景与步骤，重演「退出时正在演的那一步」。
     * 剧本变更导致场景不存在时返回 false，由调用方回退到从头开始。
     */
    private fun enterSceneAt(point: StoryProgressRepository.ResumePoint): Boolean {
        val scene = chapter?.sceneById(point.sceneId) ?: return false
        currentScene = scene
        stepIndex = point.stepIndex.coerceIn(0, scene.steps.size)
        applyBackground(scene.backgroundId)
        hideChoices()
        nextStep()
        return true
    }

    internal fun nextStep() {
        val scene = currentScene ?: return
        if (stepIndex >= scene.steps.size) {
            advanceScene()
            return
        }
        val index = stepIndex
        val step = scene.steps[stepIndex++]
        // 记录续读点：指向「正在演出的这一步」，中途退出后可原样续演
        if (!sideMode) {
            StoryProgressRepository.setResume(this, lineId, chapterIndex, scene.id, index)
        }
        when (step) {
            is StoryStep.ChangeBg -> {
                applyBackground(step.backgroundId)
                nextStep()
            }
            is StoryStep.Narrate -> showLine(step, resolve(step.text))
            is StoryStep.Say -> showLine(step, resolve(step.text))
            is StoryStep.Choice -> showChoices(step.options, scene.id, index)
            StoryStep.End -> showEnding()
        }
    }

    private fun advanceScene() {
        val scene = currentScene ?: return
        val next = chapter?.sceneAfter(scene)
        if (next == null) finishChapter() else enterScene(next)
    }

    private fun onStageClick() {
        if (cardTitle.visibility == View.VISIBLE) return
        if (ending) {
            finishChapter()
            return
        }
        if (typing) {
            completeTyping()
            return
        }
        if (choiceActive) return
        nextStep()
    }

    private fun finishChapter() {
        if (finished) return
        finished = true
        ui.removeCallbacksAndMessages(null)

        val ch = chapter
        // 支线不结算任何主线进度与好感
        if (ch == null || sideMode) {
            finish()
            return
        }

        val firstClear = StoryProgressRepository.markCompleted(this, lineId, ch.index)
        if (firstClear) {
            // 情绪结算：读完一章的满足感（重复阅读不再叠加）
            WorldStateStore.applyEvent(this, event = WorldEvent.ChapterFinished(ch.index))
        }
        if (firstClear && ch.rewardAffection > 0) {
            EconomyManager.increase(this, ch.rewardAffection)
            Toast.makeText(this, getString(R.string.story_reward_toast, ch.rewardAffection), Toast.LENGTH_SHORT).show()
        }

        // 正常读到「本章完」才做章末判定；中途跳过不判定
        if (ending && ch.sideStory != null) {
            showSideVerdict(ch)
        } else {
            finish()
        }
    }

    /**
     * 章末判定卡：显示本章好感度与阈值，解锁对应的支线番外。
     * 弹窗不可取消，确认后离开本章。
     */
    private fun showSideVerdict(ch: StoryChapter) {
        val side = ch.sideStory
        val affection = chapterAffection.coerceAtLeast(0)
        val route = ch.sideRouteFor(affection)
        if (side == null || route == null) {
            finish()
            return
        }
        StoryProgressRepository.unlockSideStory(this, lineId, ch.index, route.id)

        val message = buildString {
            append(getString(R.string.story_verdict_message, affection, side.threshold))
            append("\n\n")
            append(getString(R.string.story_verdict_unlock, route.label, route.title))
            append("\n")
            append(getString(R.string.story_verdict_hint))
        }
        ThemedDialog.show(
            context = this,
            title = getString(R.string.story_verdict_title),
            message = message,
            positiveText = getString(R.string.story_verdict_confirm),
            negativeText = null,
            iconRes = R.drawable.ic_heart,
            cancelable = false
        ) { finish() }
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        portraitCache.clear()
    }
}
