package com.example.eden.feature.memory

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.example.eden.R
import com.example.eden.core.ui.BaseActivity
import com.example.eden.core.ui.ThemedDialog
import com.example.eden.data.local.LongTermMemoryEntity
import com.example.eden.data.repository.LongTermMemoryRepository
import com.example.eden.domain.character.MikuPersona
import com.example.eden.domain.memory.MemoryCategory
import com.example.eden.ui.theme.edenColor
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「她的记忆」管理页。
 *
 * 展示三玖当前记住的关于用户的事（按类型分组），支持单条遗忘。
 * 只展示 supersededBy = 0 的有效记忆；被取代的旧事实仍留在库里，
 * 用于「你之前不是住重庆吗」这类回溯，但不在这里干扰阅读。
 */
class MemoryActivity : BaseActivity() {

    private lateinit var tvCount: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var llList: LinearLayout

    private val repository by lazy { LongTermMemoryRepository(applicationContext) }
    private val dateFormat by lazy { SimpleDateFormat("M月d日", Locale.getDefault()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_memory)

        tvCount = findViewById(R.id.tv_count)
        tvEmpty = findViewById(R.id.tv_empty)
        llList = findViewById(R.id.ll_list)
        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }
    }

    /** 从其它页面返回或删除后都重新读库，保证列表与数据库一致。 */
    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val memories = repository.all(MikuPersona.ID)
            render(memories)
        }
    }

    private fun render(memories: List<LongTermMemoryEntity>) {
        llList.removeAllViews()
        tvCount.text = getString(R.string.memory_count, memories.size)
        tvEmpty.visibility = if (memories.isEmpty()) View.VISIBLE else View.GONE

        MemoryCategory.entries.forEach { category ->
            val group = memories.filter { it.category == category.value }
            if (group.isEmpty()) return@forEach
            llList.addView(sectionHeader(getString(categoryLabelRes(category))))
            group.forEach { llList.addView(memoryCard(it)) }
        }
    }

    private fun sectionHeader(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(edenColor(R.attr.edenColorTextSecondary, R.color.eden_text_secondary))
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = dp(18)
        params.bottomMargin = dp(8)
        layoutParams = params
    }

    private fun memoryCard(memory: LongTermMemoryEntity): View {
        val card = LayoutInflater.from(this).inflate(R.layout.item_memory, llList, false)
        card.findViewById<TextView>(R.id.tv_content).text = displayContent(memory.content)
        card.findViewById<TextView>(R.id.tv_meta).text = buildString {
            append(getString(R.string.memory_importance, memory.importance))
            append(" · ")
            append(getString(R.string.memory_mention_count, memory.mentionCount))
            append(" · ")
            append(dateFormat.format(Date(memory.lastMentionedAt)))
        }
        card.findViewById<ImageView>(R.id.btn_delete).setOnClickListener {
            confirmForget(memory)
        }
        return card
    }

    private fun confirmForget(memory: LongTermMemoryEntity) {
        ThemedDialog.show(
            context = this,
            title = getString(R.string.memory_delete_title),
            message = getString(R.string.memory_delete_message, displayContent(memory.content)),
            positiveText = getString(R.string.memory_delete_confirm),
            iconRes = R.drawable.ic_delete,
            iconTint = edenColor(R.attr.edenColorPrimaryPressed, R.color.eden_primary_pressed)
        ) {
            lifecycleScope.launch {
                repository.forget(memory.id)
                Toast.makeText(this@MemoryActivity, R.string.memory_deleted, Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
    }

    /**
     * 「用户」只是抽取时用的内部主语，展示时统一换成「ta」，
     * 读起来像是她自己记下的事，而不是系统档案。
     */
    private fun displayContent(content: String): String = content
        .replace("用户的姓名叫", "ta叫")
        .replace("用户的名字叫", "ta叫")
        .replace("用户名叫", "ta叫")
        .replace("用户", "ta")

    private fun categoryLabelRes(category: MemoryCategory): Int = when (category) {
        MemoryCategory.IDENTITY -> R.string.memory_category_identity
        MemoryCategory.PREFERENCE -> R.string.memory_category_preference
        MemoryCategory.EVENT -> R.string.memory_category_event
        MemoryCategory.RELATIONSHIP -> R.string.memory_category_relationship
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
