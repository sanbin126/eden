package com.example.eden

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
import androidx.appcompat.app.AppCompatActivity
import com.example.eden.character.Characters
import com.example.eden.character.StoryChapter
import com.example.eden.character.StoryChoice
import com.example.eden.character.StoryLibrary
import com.example.eden.character.StoryScene
import com.example.eden.character.StoryStep
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
class StoryPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LINE_ID = "extra_line_id"
        const val EXTRA_CHAPTER_INDEX = "extra_chapter_index"

        /** 打字机速度：每字间隔 */
        private const val TYPE_INTERVAL_MS = 35L

        /** 自动播放时，一句读完后停留时长 */
        private const val AUTO_DELAY_MS = 1600L

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
    }

    private lateinit var lineId: String
    private var chapterIndex: Int = 1
    private var chapter: StoryChapter? = null

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

    /** 已到「本章完」，再点一次才结算离开 */
    private var ending = false

    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_story_player)

        lineId = intent.getStringExtra(EXTRA_LINE_ID) ?: Characters.current.id
        chapterIndex = intent.getIntExtra(EXTRA_CHAPTER_INDEX, 1)
        chapter = StoryLibrary.loadChapter(this, lineId, chapterIndex)
        val ch = chapter
        if (ch == null || ch.scenes.isEmpty()) {
            Toast.makeText(this, getString(R.string.story_home_empty), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        bindViews()
        bindTitleCard(ch)
        // 记录续读位置：首页「继续阅读」据此定位
        StoryProgressManager.setLastReadChapter(this, lineId, chapterIndex)
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
        cardTitle.findViewById<View>(R.id.btn_title_start).setOnClickListener { startStory() }
        cardTitle.findViewById<View>(R.id.btn_title_skip).setOnClickListener { finishChapter() }
    }

    private fun startStory() {
        if (chapter == null) return
        cardTitle.animate().alpha(0f).setDuration(280)
            .withEndAction { cardTitle.visibility = View.GONE }
            .start()
        enterScene(chapter!!.scenes.first())
    }

    // ==================== 场景 / 步骤推进 ====================

    private fun enterScene(scene: StoryScene) {
        currentScene = scene
        stepIndex = 0
        applyBackground(scene.backgroundId)
        hideChoices()
        nextStep()
    }

    private fun nextStep() {
        val scene = currentScene ?: return
        if (stepIndex >= scene.steps.size) {
            advanceScene()
            return
        }
        when (val step = scene.steps[stepIndex++]) {
            is StoryStep.ChangeBg -> {
                applyBackground(step.backgroundId)
                nextStep()
            }
            is StoryStep.Narrate -> showLine(step, resolve(step.text))
            is StoryStep.Say -> showLine(step, resolve(step.text))
            is StoryStep.Choice -> showChoices(step.options)
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

    private fun showChoices(options: List<StoryChoice>) {
        cancelAuto()
        cancelTypewriter()
        tvDialogNext.visibility = View.INVISIBLE
        choiceActive = true

        llChoices.removeAllViews()
        val inflater = LayoutInflater.from(this)
        options.forEach { option ->
            val item = inflater.inflate(R.layout.item_story_choice, llChoices, false) as TextView
            item.text = resolve(option.text)
            item.setOnClickListener {
                // 首次点击选中，再次点击确认
                if (item.isSelected) confirmChoice(option) else selectOnly(item)
            }
            llChoices.addView(item)
        }
        llChoices.visibility = View.VISIBLE
    }

    private fun selectOnly(target: View) {
        for (i in 0 until llChoices.childCount) {
            llChoices.getChildAt(i).isSelected = false
        }
        target.isSelected = true
    }

    private fun confirmChoice(option: StoryChoice) {
        choiceActive = false
        hideChoices()
        applyAffection(option.affection)

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
            AffectionManager.increase(this, delta)
        } else {
            AffectionManager.decrease(this, -delta)
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
        fullText = getString(R.string.story_chapter_end)
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
        if (ch != null) {
            val firstClear = StoryProgressManager.markCompleted(this, lineId, ch.index)
            if (firstClear && ch.rewardAffection > 0) {
                AffectionManager.increase(this, ch.rewardAffection)
                Toast.makeText(this, getString(R.string.story_reward_toast, ch.rewardAffection), Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }

    // ==================== 背景 / 立绘 / 文本 ====================

    private fun applyBackground(backgroundId: String) {
        val bg = chapter?.backgrounds?.get(backgroundId) ?: return
        val top = parseColor(bg.topColor, Color.parseColor("#FFEEF4"))
        val bottom = parseColor(bg.bottomColor, Color.parseColor("#CBB8DE"))
        ivStoryBg.setImageDrawable(
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom))
        )
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
        NicknameManager.getFormal(this).ifEmpty { NicknameManager.getCall(this) }

    /** 剧本占位符替换：{meFull} → 全名，{me} → 小名。 */
    private fun resolve(text: String): String =
        text.replace("{meFull}", playerName()).replace("{me}", NicknameManager.getCall(this))

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        portraitCache.clear()
    }
}
