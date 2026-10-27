package com.example.eden.feature.story


import com.example.eden.R
import com.example.eden.core.ui.BaseActivity
import com.example.eden.domain.model.ChatMessage
import com.example.eden.domain.model.MessageType
import com.example.eden.feature.chat.ChatAdapter
import com.example.eden.ui.theme.edenColor
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.eden.domain.character.MilestoneStories
import com.example.eden.domain.character.MilestoneStory
import com.example.eden.domain.character.StoryOption
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * 好感度里程碑小剧场界面：以聊天框样式演出三玖的专属短剧。
 *
 * 两种模式：
 * - PLAY（首次触发）：逐句演出 → 抛出选项等用户选择 → 按选项给出固定回应 → 收尾，结束即标记完成。
 * - REPLAY（从列表回看）：按历史选择记录只读重放整段剧情，不产生新记录。
 *
 * 通过 Intent extra [EXTRA_MILESTONE] 传入里程碑值，[EXTRA_REPLAY] 指定是否回看模式。
 */
class MilestoneStoryActivity : BaseActivity() {

    companion object {
        const val EXTRA_MILESTONE = "milestone"
        const val EXTRA_REPLAY = "replay"

        /** 每句台词之间的停顿，模拟对话节奏 */
        private const val LINE_DELAY_MS = 1200L

        /** 用户选择后到角色回应之间的停顿 */
        private const val REPLY_DELAY_MS = 2000L

        /** 左滑退出的最小水平位移（dp），超过且横向占主导时关闭本页 */
        private const val SWIPE_EXIT_DISTANCE_DP = 80
    }

    private lateinit var rvChat: RecyclerView
    private lateinit var llOptions: LinearLayout
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var btnBack: Button
    private lateinit var adapter: ChatAdapter

    private lateinit var story: MilestoneStory
    private var milestone = 0
    private var replay = false

    private val messages = mutableListOf<ChatMessage>()

    /** PLAY 模式下用户的选择，用于结束时落库记录 */
    private val choices = mutableListOf<String>()

    /** 左滑退出：记录按下位置，抬起时判断是否构成一次横向左滑 */
    private var touchDownX = 0f
    private var touchDownY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        milestone = intent.getIntExtra(EXTRA_MILESTONE, 0)
        replay = intent.getBooleanExtra(EXTRA_REPLAY, false)
        val loaded = MilestoneStories.forMilestone(milestone)
        if (loaded == null) {
            finish()
            return
        }
        story = loaded

        setContentView(R.layout.activity_milestone_story)
        initViews()

        if (replay) playReplay() else playStory()
    }

    private fun initViews() {
        rvChat = findViewById(R.id.rv_chat)
        llOptions = findViewById(R.id.ll_options)
        tvTitle = findViewById(R.id.tv_story_title)
        tvSubtitle = findViewById(R.id.tv_story_subtitle)
        btnBack = findViewById(R.id.btn_back)

        tvTitle.text = story.title
        tvSubtitle.text = "好感度 $milestone"
        btnBack.setOnClickListener { finish() }

        adapter = ChatAdapter()
        rvChat.layoutManager = LinearLayoutManager(this)
        rvChat.adapter = adapter
    }

    /**
     * 全局监听触摸，识别「左滑退出」手势。
     * 只观察不消费，纵向滚动列表等原有交互不受影响。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = ev.x
                touchDownY = ev.y
            }
            MotionEvent.ACTION_UP -> {
                val dx = touchDownX - ev.x // 左滑为正
                val dy = abs(touchDownY - ev.y)
                if (dx > dp(SWIPE_EXIT_DISTANCE_DP) && dx > dy * 1.5f) finish()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // ==================== PLAY：交互式演出 ====================

    private fun playStory() {
        lifecycleScope.launch {
            story.intro.forEach {
                addReceived(it)
                delay(LINE_DELAY_MS)
            }
            for (round in story.rounds) {
                addReceived(round.mikuLine)
                delay(LINE_DELAY_MS)

                val option = awaitChoice(round.options)
                choices.add(option.text)
                addSent(option.text)
                delay(REPLY_DELAY_MS)
                addReceived(option.reply)
                delay(LINE_DELAY_MS)
            }
            story.outro.forEach {
                addReceived(it)
                delay(LINE_DELAY_MS)
            }
            addEndLine()
            // 演出结束即落库记录（无需再点按钮），随后用户可左滑或返回退出
            MilestoneRepository.markCompleted(this@MilestoneStoryActivity, milestone, choices)
        }
    }

    /** 挂起等待用户点选某个选项；选项已由 [showOptions] 渲染到底部。 */
    private suspend fun awaitChoice(options: List<StoryOption>): StoryOption =
        suspendCancellableCoroutine { cont ->
            showOptions(options) { option ->
                hideOptions()
                if (cont.isActive) cont.resume(option)
            }
        }

    // ==================== REPLAY：只读重放 ====================

    private fun playReplay() {
        val record = MilestoneRepository.recordOf(this, milestone)
        story.intro.forEach { messages.add(ChatMessage(content = it, type = MessageType.TYPE_RECEIVED)) }
        story.rounds.forEachIndexed { index, round ->
            messages.add(ChatMessage(content = round.mikuLine, type = MessageType.TYPE_RECEIVED))
            val chosen = record.getOrNull(index)
                ?.let { text -> round.options.firstOrNull { it.text == text } }
            if (chosen != null) {
                messages.add(ChatMessage(content = chosen.text, type = MessageType.TYPE_SENT))
                messages.add(ChatMessage(content = chosen.reply, type = MessageType.TYPE_RECEIVED))
            }
        }
        story.outro.forEach { messages.add(ChatMessage(content = it, type = MessageType.TYPE_RECEIVED)) }
        messages.add(ChatMessage(content = "", type = MessageType.TYPE_STORY_END))

        // 回看模式：整段剧情一次性呈现，不再逐句加载，定位到顶部
        adapter.submit(messages.toList())
        rvChat.scrollToPosition(0)
    }

    // ==================== 选项渲染 ====================

    private fun showOptions(options: List<StoryOption>, onClick: (StoryOption) -> Unit) {
        llOptions.removeAllViews()
        options.forEach { option ->
            llOptions.addView(buildOptionView(option.text) { onClick(option) })
        }
    }

    private fun hideOptions() {
        llOptions.removeAllViews()
    }

    private fun buildOptionView(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(edenColor(R.attr.edenColorAccent, R.color.eden_accent))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = getDrawable(R.drawable.bg_outline_button)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    // ==================== 列表操作 ====================

    private fun addReceived(content: String) {
        messages.add(ChatMessage(content = content, type = MessageType.TYPE_RECEIVED))
        submit()
    }

    private fun addSent(content: String) {
        messages.add(ChatMessage(content = content, type = MessageType.TYPE_SENT))
        submit()
    }

    /** 在剧情最后追加一条横线，表示演出结束 */
    private fun addEndLine() {
        messages.add(ChatMessage(content = "", type = MessageType.TYPE_STORY_END))
        submit()
    }

    private fun submit() {
        adapter.submit(messages.toList())
        rvChat.smoothScrollToPosition(adapter.itemCount - 1)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
