package com.example.eden.feature.story


import com.example.eden.R
import com.example.eden.core.ui.BaseActivity
import com.example.eden.core.ui.ThemedDialog
import com.example.eden.data.repository.ProfileRepository
import com.example.eden.domain.economy.EconomyManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.eden.domain.character.Characters
import com.example.eden.domain.character.StoryChapter
import com.example.eden.domain.character.StoryChoice
import com.example.eden.domain.character.StoryLibrary
import com.example.eden.domain.character.StoryScene
import com.example.eden.domain.character.StoryStep
import com.example.eden.domain.world.WorldEvent
import com.example.eden.domain.world.WorldStateStore
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 剧情演出播放器：galgame 式逐句推进。
 *
 * 演出流程：章节标题卡 → 逐场景逐句演出（打字机 + 立绘 + 渐变背景 → 选项卡 → 章节完）。
 *
 * ## 隔离
 * - 线路 [lineId] 决定剧本来源 `assets/story/<lineId>/` 与进度 `story_prefs_<lineId>`；
 * - 剧中说话人 [StoryStep.Say.who] 决定立绘来源 `assets/portrait/<castId>/`。
 * 两者都来自 Intent / 剧本，切角色时不会互相污染。
 */
class StoryPlayerActivity : BaseActivity() {

    companion object {
        const val EXTRA_LINE_ID = "extra_line_id"
        const val EXTRA_CHAPTER_INDEX = "extra_chapter_index"

        /** 章末支线（番外）剧本文件名，不含扩展名；传入后进入支线播放模式。 */
        const val EXTRA_SIDE_FILE = "extra_side_file"

        /** 打字机速度：每字间隔 */
        private const val TYPE_INTERVAL_MS = 35L

        /** 自动播放时，一句读完后停留时长 */
        private const val AUTO_DELAY_MS = 1600L

        /** 「重温」时，选项默认选中的展示时长，之后自动按上次的选择继续 */
        private const val REPLAY_CHOICE_DELAY_MS = 1200L

        /** 立绘解码目标高度（px），足够清晰又省内存 */
        private const val PORTRAIT_TARGET_PX = 1300

        /** 立绘默认显示高度（dp），与布局中的基准值一致 */
        private const val PORTRAIT_HEIGHT_DEFAULT_DP = 360

        /**
         * 立绘显示高度覆盖（dp）。四叶的立绘是跳跃欢呼姿态（单臂高举、抬腿），
         * 身体在画布中的占比明显小于其他姐妹，同高度下视觉上会小一圈，故单独放大。
         */
        private val PORTRAIT_HEIGHT_OVERRIDE_DP = mapOf("yotsuba" to 415)

        /** 玩家角色的说话人 id（剧本中用 you 表示） */
        private const val YOU = "you"

        /** CG 插画在 assets 下的目录前缀；CG 自带人物，演出期间隐藏立绘 */
        private const val CG_ROOT = "story_cg/"
    }

    /** 标题卡的进入形态：首次打开 / 中途续读 / 已通关重温。 */
    private enum class EntryMode { FIRST, RESUME, REPLAY }

    private lateinit var lineId: String
    private var chapterIndex: Int = 1
    private var chapter: StoryChapter? = null

    /** 支线剧本文件名；非空时处于支线播放模式（不写主线进度、不结算好感）。 */
    private var sideFile: String? = null
    private val sideMode: Boolean get() = sideFile != null

    private lateinit var ivStoryBg: ImageView
    private lateinit var ivStoryPortrait: ImageView
    private lateinit var tvSceneLabel: TextView
    private lateinit var llChoices: LinearLayout
    private lateinit var tvDialogName: TextView
    private lateinit var tvDialogText: TextView
    private lateinit var tvDialogNext: TextView
    private lateinit var btnStoryBack: View
    private lateinit var btnStoryAuto: TextView
    private lateinit var btnStorySkip: TextView
    private lateinit var cardTitle: View
    private lateinit var btnTitleStart: TextView
    private lateinit var btnTitleRestart: TextView

    // ==================== 演出状态 ====================
    private val ui = Handler(Looper.getMainLooper())
    private val portraitCache = mutableMapOf<String, Bitmap>()

    private var currentScene: StoryScene? = null
    private var stepIndex = 0

    private var typing = false
    private var fullText = ""
    private var typeRunnable: Runnable? = null

    private var autoMode = false
    private var autoRunnable: Runnable? = null

    /** 选项卡展示中：此时点击舞台不推进 */
    private var choiceActive = false

    /** 重温模式：选项不可再选，自动沿上次的选择继续，且不重复结算好感 */
    private var replayMode = false

    /** 当前背景是否为 CG 插画：CG 内的三玖是全屏画面，此时不再叠加立绘 */
    private var bgIsCg = false

    /** 已到「本章完」，再点一次才结算离开 */
    private var ending = false

    private var finished = false

    /** 本章选项累计好感度（章末判定用，不含全局好感与通关奖励）。 */
    private var chapterAffection = 0

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

    private fun enterScene(scene: StoryScene) {
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

    private fun nextStep() {
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

    // ==================== 台词 / 打字机 ====================

    private fun showLine(step: StoryStep?, text: String) {
        cancelTypewriter()
        cancelAuto()
        tvDialogNext.visibility = View.INVISIBLE

        when (step) {
            is StoryStep.Say -> if (step.who == YOU) {
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

    private fun typeText(text: String) {
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
                    ui.postDelayed(this, TYPE_INTERVAL_MS)
                } else {
                    onTypingDone()
                }
            }
        }
        typeRunnable = runnable
        ui.postDelayed(runnable, TYPE_INTERVAL_MS)
    }

    private fun onTypingDone() {
        typing = false
        typeRunnable = null
        tvDialogNext.visibility = View.VISIBLE
        scheduleAuto()
    }

    private fun completeTyping() {
        cancelTypewriter()
        tvDialogText.text = fullText
        typing = false
        tvDialogNext.visibility = View.VISIBLE
        scheduleAuto()
    }

    private fun cancelTypewriter() {
        typeRunnable?.let { ui.removeCallbacks(it) }
        typeRunnable = null
    }

    // ==================== 选项卡 ====================

    private fun showChoices(options: List<StoryChoice>, sceneId: String, choiceIndex: Int) {
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
                ui.postDelayed({ confirmChoice(pick, sceneId, choiceIndex, lockedIndex) }, REPLAY_CHOICE_DELAY_MS)
            }
        }
    }

    private fun selectOnly(target: View) {
        for (i in 0 until llChoices.childCount) {
            llChoices.getChildAt(i).isSelected = false
        }
        target.isSelected = true
    }

    private fun confirmChoice(option: StoryChoice, sceneId: String, choiceIndex: Int, optionIndex: Int) {
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

    private fun hideChoices() {
        llChoices.visibility = View.GONE
        llChoices.removeAllViews()
    }

    private fun applyAffection(delta: Int) {
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

    private fun toggleAuto() {
        autoMode = !autoMode
        btnStoryAuto.alpha = if (autoMode) 1f else 0.55f
        if (autoMode) {
            if (!typing && !choiceActive && !ending) scheduleAuto()
        } else {
            cancelAuto()
        }
    }

    private fun scheduleAuto() {
        if (!autoMode || choiceActive || ending) return
        cancelAuto()
        val runnable = Runnable { nextStep() }
        autoRunnable = runnable
        ui.postDelayed(runnable, AUTO_DELAY_MS)
    }

    private fun cancelAuto() {
        autoRunnable?.let { ui.removeCallbacks(it) }
        autoRunnable = null
    }

    private fun showEnding() {
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

    // ==================== 背景 / 立绘 / 文本 ====================

    private fun applyBackground(backgroundId: String) {
        val bg = chapter?.backgrounds?.get(backgroundId) ?: return
        bgIsCg = bg.image?.startsWith(CG_ROOT) == true
        // 优先用真实背景图；缺失或解码失败时回退到上下渐变，保证不崩
        val bitmap = StoryLibrary.loadBackground(this, bg.image, resources.displayMetrics.heightPixels)
        if (bitmap != null) {
            ivStoryBg.setImageBitmap(bitmap)
        } else {
            val top = parseColor(bg.topColor, Color.parseColor("#FFEEF4"))
            val bottom = parseColor(bg.bottomColor, Color.parseColor("#CBB8DE"))
            ivStoryBg.setImageDrawable(
                GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom))
            )
        }
        tvSceneLabel.text = bg.label
    }

    private fun parseColor(hex: String, fallback: Int): Int =
        runCatching { Color.parseColor(hex) }.getOrDefault(fallback)

    /**
     * 玩家（"you"）台词时的立绘演出。
     *
     * 目前玩家还没有立绘资源，所以直接清空舞台上的角色立绘。
     * 后续接入「自定义玩家立绘」时，只需在这里改成按玩家配置的图片展示
     * （例如从自定义立绘目录加载，再走 [applyPortraitHeight] 之类的高度规则），
     * 台词 / 旁白 / 分支流程都不需要再改。
     */
    private fun showPlayerPortrait() {
        ivStoryPortrait.visibility = View.GONE
    }

    /**
     * 展示说话人立绘：固定靠左（气泡左上角），底边与对话框底边对齐，
     * 下半身被不透明浅色气泡遮住 → 呈「露出半身」的 galgame 演出。
     * 高度按角色覆写（见 [PORTRAIT_HEIGHT_OVERRIDE_DP]），宽度由 adjustViewBounds 等比得出。
     */
    private fun showPortrait(castId: String, face: String?) {
        // CG 背景期间隐藏立绘：CG 里已经有角色全身像，叠加会变成「两个三玖」
        if (bgIsCg) {
            ivStoryPortrait.visibility = View.GONE
            return
        }
        val key = "$castId|${face.orEmpty()}"
        val bitmap = portraitCache[key]
            ?: StoryLibrary.loadPortrait(this, castId, face, PORTRAIT_TARGET_PX)?.also { portraitCache[key] = it }
        if (bitmap == null) {
            ivStoryPortrait.visibility = View.GONE
            return
        }
        applyPortraitHeight(castId)
        ivStoryPortrait.setImageBitmap(bitmap)
        ivStoryPortrait.visibility = View.VISIBLE
    }

    private fun applyPortraitHeight(castId: String) {
        val dp = PORTRAIT_HEIGHT_OVERRIDE_DP[castId] ?: PORTRAIT_HEIGHT_DEFAULT_DP
        val px = (dp * resources.displayMetrics.density).roundToInt()
        val lp = ivStoryPortrait.layoutParams
        if (lp.height != px) {
            lp.height = px
            ivStoryPortrait.layoutParams = lp
        }
    }

    /** 玩家角色名：优先全名，未设置则退回小名。 */
    private fun playerName(): String =
        ProfileRepository.getFormal(this).ifEmpty { ProfileRepository.getCall(this) }

    /** 剧本占位符替换：{meFull} → 全名，{me} → 小名。 */
    private fun resolve(text: String): String =
        text.replace("{meFull}", playerName()).replace("{me}", ProfileRepository.getCall(this))

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        portraitCache.clear()
    }
}
