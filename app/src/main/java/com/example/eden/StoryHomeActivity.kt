package com.example.eden

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.eden.character.Characters
import com.example.eden.character.StoryChapter
import com.example.eden.character.StoryLibrary

/**
 * 剧情板块首页：按当前线路列出章节，提供「继续阅读」入口。
 *
 * ## 隔离
 * 线路 id 取 [Characters.current].id。剧本从 `assets/story/<lineId>/` 读，
 * 进度从 [StoryProgressManager] 按同一 lineId 读，切角色时互不影响。
 *
 * 解锁规则：前置章节通关 + 好感门槛（见 [StoryProgressManager.isUnlocked]）。
 * 未解锁的章节仍会展示（灰显 + 锁图标 + 解锁条件），让用户知道目标。
 */
class StoryHomeActivity : AppCompatActivity() {

    private val lineId: String get() = Characters.current.id

    private lateinit var llChapters: LinearLayout
    private lateinit var cardResume: View
    private lateinit var tvResumeTitle: TextView
    private lateinit var tvResumeScene: TextView
    private lateinit var tvEmpty: TextView

    private var chapters: List<StoryChapter> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_story_home)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        llChapters = findViewById(R.id.ll_chapters)
        cardResume = findViewById(R.id.card_resume)
        tvResumeTitle = findViewById(R.id.tv_resume_title)
        tvResumeScene = findViewById(R.id.tv_resume_scene)
        tvEmpty = findViewById(R.id.tv_story_empty)
    }

    override fun onResume() {
        super.onResume()
        // 从播放器返回后刷新：通关状态、续读位置都会变
        refresh()
    }

    private fun refresh() {
        chapters = StoryLibrary.loadLine(this, lineId)

        val empty = chapters.isEmpty()
        tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        llChapters.visibility = if (empty) View.GONE else View.VISIBLE

        buildChapters()
        bindResumeCard()
    }

    private fun buildChapters() {
        llChapters.removeAllViews()
        val inflater = LayoutInflater.from(this)
        chapters.forEach { chapter ->
            val item = inflater.inflate(R.layout.item_story_chapter, llChapters, false)
            bindChapterItem(item, chapter)
            llChapters.addView(item)
        }
    }

    private fun bindChapterItem(item: View, chapter: StoryChapter) {
        val tvIndex = item.findViewById<TextView>(R.id.tv_chapter_index)
        val tvTitle = item.findViewById<TextView>(R.id.tv_chapter_title)
        val tvMeta = item.findViewById<TextView>(R.id.tv_chapter_meta)
        val ivLock = item.findViewById<ImageView>(R.id.iv_chapter_lock)
        val tvBadge = item.findViewById<TextView>(R.id.tv_chapter_badge)
        val ivChevron = item.findViewById<ImageView>(R.id.iv_chapter_chevron)

        val unlocked = StoryProgressManager.isUnlocked(this, lineId, chapter)
        val completed = StoryProgressManager.isCompleted(this, lineId, chapter.index)

        tvIndex.text = "%02d".format(chapter.index)
        tvTitle.text = chapter.title

        if (unlocked) {
            tvMeta.text = getString(R.string.story_item_meta, chapter.chapterLabel, chapter.scenes.size)
            ivLock.visibility = View.GONE
            ivChevron.visibility = View.VISIBLE
            when {
                completed -> {
                    tvBadge.text = getString(R.string.story_chapter_done)
                    tvBadge.visibility = View.VISIBLE
                }
                StoryProgressManager.lastReadChapter(this, lineId) == chapter.index -> {
                    tvBadge.text = getString(R.string.story_chapter_progress)
                    tvBadge.visibility = View.VISIBLE
                }
                else -> tvBadge.visibility = View.GONE
            }
            item.alpha = 1f
        } else {
            tvMeta.text = StoryProgressManager.lockReason(this, lineId, chapter).orEmpty()
            ivLock.visibility = View.VISIBLE
            ivChevron.visibility = View.GONE
            tvBadge.visibility = View.GONE
            item.alpha = 0.5f
        }

        item.setOnClickListener {
            if (unlocked) {
                openChapter(chapter.index)
            } else {
                Toast.makeText(this, StoryProgressManager.lockReason(this, lineId, chapter).orEmpty(), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun bindResumeCard() {
        val last = StoryProgressManager.lastReadChapter(this, lineId)
        val chapter = chapters.firstOrNull { it.index == last }
        if (chapter == null || !StoryProgressManager.isUnlocked(this, lineId, chapter)) {
            cardResume.visibility = View.GONE
            return
        }
        cardResume.visibility = View.VISIBLE
        tvResumeTitle.text = chapter.title
        tvResumeScene.text = "${chapter.chapterLabel} · ${getString(R.string.story_resume_hint)}"
        cardResume.setOnClickListener { openChapter(chapter.index) }
    }

    private fun openChapter(index: Int) {
        startActivity(
            Intent(this, StoryPlayerActivity::class.java)
                .putExtra(StoryPlayerActivity.EXTRA_LINE_ID, lineId)
                .putExtra(StoryPlayerActivity.EXTRA_CHAPTER_INDEX, index)
        )
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
