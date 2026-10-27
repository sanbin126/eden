package com.example.eden.feature.diary


import com.example.eden.R
import com.example.eden.core.ui.BaseActivity
import com.example.eden.ui.theme.edenColor
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.example.eden.data.local.DiaryEntry
import com.example.eden.data.repository.DiaryRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 日记 / 心情日记预览界面。
 *
 * 核心交互：选心情 → 写正文 → 选标签 → 保存。
 * 保存时写入 [DiaryRepository]（Room），保存成功后在后台把日记压缩成一句话摘要，
 * 供三玖在你之后进入聊天时自然地提起；随后刷新底部"过往日记"列表。
 */
class DiaryActivity : BaseActivity() {

    private lateinit var btnBack: ImageView
    private lateinit var tvToday: TextView
    private lateinit var llMoods: LinearLayout
    private lateinit var etContent: EditText
    private lateinit var llTags: LinearLayout
    private lateinit var btnSave: Button
    private lateinit var tvEmpty: TextView
    private lateinit var llHistory: LinearLayout

    private val repository by lazy { DiaryRepository(applicationContext) }

    private val moods = listOf(
        "😊" to "开心",
        "😌" to "平静",
        "😢" to "低落",
        "😣" to "烦躁",
        "😴" to "疲惫"
    )
    private val tags = listOf("工作", "学习", "朋友", "家人", "健康", "其他")

    private val moodChipViews = mutableListOf<LinearLayout>()
    private val tagChipViews = mutableListOf<TextView>()
    private var selectedMood: Int = -1
    private val selectedTags = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diary)

        initViews()
        setupMoodChips()
        setupTagChips()
        setupListeners()
        updateTodayLabel()
        lifecycleScope.launch { loadHistory() }
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btn_back)
        tvToday = findViewById(R.id.tv_today)
        llMoods = findViewById(R.id.ll_moods)
        etContent = findViewById(R.id.et_content)
        llTags = findViewById(R.id.ll_tags)
        btnSave = findViewById(R.id.btn_save)
        tvEmpty = findViewById(R.id.tv_empty)
        llHistory = findViewById(R.id.ll_history)
    }

    private fun setupMoodChips() {
        // 5 个心情等宽分布在一行
        val textColorUnselected = edenColor(R.attr.edenColorIcon, R.color.eden_icon)
        val textColorSelected = edenColor(R.attr.edenColorOnPrimary, R.color.eden_on_primary)
        moods.forEachIndexed { index, (emoji, label) ->
            val chip = LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = dp(8)
                }
            }
            val emojiTv = TextView(this).apply {
                text = emoji
                textSize = 24f
                gravity = Gravity.CENTER
            }
            val labelTv = TextView(this).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(textColorUnselected)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(2)
                }
            }
            chip.addView(emojiTv)
            chip.addView(labelTv)
            chip.setOnClickListener {
                selectedMood = index
                moodChipViews.forEachIndexed { i, c ->
                    c.isSelected = (i == index)
                    (c.getChildAt(1) as TextView).setTextColor(
                        if (i == index) textColorSelected else textColorUnselected
                    )
                }
            }
            moodChipViews.add(chip)
            llMoods.addView(chip)
        }
    }

    private fun setupTagChips() {
        val textColorUnselected = edenColor(R.attr.edenColorIcon, R.color.eden_icon)
        val textColorSelected = edenColor(R.attr.edenColorOnPrimary, R.color.eden_on_primary)
        tags.forEachIndexed { index, tag ->
            val chip = TextView(this).apply {
                text = tag
                textSize = 13f
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                setTextColor(textColorUnselected)
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    if (index > 0) marginStart = dp(10)
                }
            }
            chip.setOnClickListener {
                if (selectedTags.contains(tag)) {
                    selectedTags.remove(tag)
                    chip.isSelected = false
                    chip.setTextColor(textColorUnselected)
                } else {
                    selectedTags.add(tag)
                    chip.isSelected = true
                    chip.setTextColor(textColorSelected)
                }
            }
            tagChipViews.add(chip)
            llTags.addView(chip)
        }
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }
        btnSave.setOnClickListener { onSave() }
    }

    private fun onSave() {
        val content = etContent.text.toString().trim()
        if (selectedMood == -1) {
            Toast.makeText(this, "选个今天的心情吧", Toast.LENGTH_SHORT).show()
            return
        }
        if (content.isEmpty()) {
            Toast.makeText(this, "写点什么再保存吧", Toast.LENGTH_SHORT).show()
            return
        }

        val (emoji, mood) = moods[selectedMood]
        val entry = DiaryEntry(
            date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()),
            mood = mood,
            moodEmoji = emoji,
            tags = selectedTags.toList(),
            content = content,
            createdAt = System.currentTimeMillis()
        )
        Toast.makeText(this, "三玖收到了你的日记，等下会找你聊聊 ❤", Toast.LENGTH_LONG).show()
        resetForm()

        lifecycleScope.launch {
            // 先落库保证不丢，再在后台把日记压缩成一句话摘要（失败则保留本地兜底摘要）
            val id = repository.save(entry)
            loadHistory()
            repository.summarize(id, entry)
        }
    }

    /** 清空正文并取消所有心情 / 标签的选中态 */
    private fun resetForm() {
        etContent.setText("")
        selectedMood = -1
        val unselected = edenColor(R.attr.edenColorIcon, R.color.eden_icon)
        moodChipViews.forEach { c ->
            c.isSelected = false
            (c.getChildAt(1) as TextView).setTextColor(unselected)
        }
        selectedTags.clear()
        tagChipViews.forEach { c ->
            c.isSelected = false
            c.setTextColor(unselected)
        }
    }

    private fun updateTodayLabel() {
        val weekdays = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        val cal = Calendar.getInstance()
        val text = "${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日 ${weekdays[cal.get(Calendar.DAY_OF_WEEK) - 1]}"
        tvToday.text = text
    }

    private suspend fun loadHistory() {
        val entries = repository.getAll()
        llHistory.removeAllViews()
        if (entries.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            return
        }
        tvEmpty.visibility = View.GONE
        val now = Calendar.getInstance()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateFmt = SimpleDateFormat("M月d日", Locale.getDefault())
        entries.forEach { entry ->
            val card = LayoutInflater.from(this).inflate(R.layout.item_diary_entry, llHistory, false)
            card.findViewById<TextView>(R.id.tv_emoji).text = entry.moodEmoji
            val dateText = if (entry.date == today) {
                "今天 ${timeFmt.format(Date(entry.createdAt))}"
            } else {
                "${dateFmt.format(Date(entry.createdAt))} ${timeFmt.format(Date(entry.createdAt))}"
            }
            card.findViewById<TextView>(R.id.tv_date).text = dateText
            card.findViewById<TextView>(R.id.tv_tags).text =
                if (entry.tags.isEmpty()) entry.mood else listOf(entry.mood, *entry.tags.toTypedArray()).joinToString(" · ")
            card.findViewById<TextView>(R.id.tv_content).text = entry.content
            llHistory.addView(card)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
