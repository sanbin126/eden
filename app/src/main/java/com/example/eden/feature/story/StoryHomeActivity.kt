package com.example.eden.feature.story


import com.example.eden.R
import com.example.eden.core.ui.BaseActivity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.eden.domain.character.Characters
import com.example.eden.domain.character.StoryChapter
import com.example.eden.domain.character.StoryLibrary
import com.example.eden.domain.character.StorySideRoute
import com.example.eden.domain.character.StorySideStory
import java.util.Locale

/**
 * 剧情板块首页：按当前线路列出章节，提供「继续阅读」入口。
 *
 * ## 隔离
 * 线路 id 取 [Characters.current].id。剧本从 `assets/story/<lineId>/` 读，
 * 进度从 [StoryProgressRepository] 按同一 lineId 读，切角色时互不影响。
 *
 * 解锁规则：前置章节通关 + 好感门槛（见 [StoryProgressRepository.isUnlocked]）。
 * 未解锁的章节仍会展示（灰显 + 锁图标 + 解锁条件），让用户知道目标。
 */
class StoryHomeActivity : BaseActivity() {

    private val lineId: String get() = Characters.current.id

    private lateinit var llChapters: LinearLayout
    private lateinit var tvSideHeader: TextView
    private lateinit var llSides: LinearLayout
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
        tvSideHeader = findViewById(R.id.tv_side_header)
        llSides = findViewById(R.id.ll_sides)
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
        buildSideStories()
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

        val unlocked = StoryProgressRepository.isUnlocked(this, lineId, chapter)
        val completed = StoryProgressRepository.isCompleted(this, lineId, chapter.index)

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
                StoryProgressRepository.lastReadChapter(this, lineId) == chapter.index -> {
                    tvBadge.text = getString(R.string.story_chapter_progress)
                    tvBadge.visibility = View.VISIBLE
                }
                else -> tvBadge.visibility = View.GONE
            }
            item.alpha = 1f
        } else {
            tvMeta.text = StoryProgressRepository.lockReason(this, lineId, chapter).orEmpty()
            ivLock.visibility = View.VISIBLE
            ivChevron.visibility = View.GONE
            tvBadge.visibility = View.GONE
            item.alpha = 0.5f
        }

        item.setOnClickListener {
            if (unlocked) {
                openChapter(chapter.index)
            } else {
                Toast.makeText(this, StoryProgressRepository.lockReason(this, lineId, chapter).orEmpty(), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 番外区块：列出各章的章末支线，未解锁的显示解锁条件。 */
    private fun buildSideStories() {
        llSides.removeAllViews()
        val entries = chapters.flatMap { chapter ->
            val side = chapter.sideStory ?: return@flatMap emptyList()
            side.routes.map { Triple(chapter, side, it) }
        }

        val visible = entries.isNotEmpty()
        tvSideHeader.visibility = if (visible) View.VISIBLE else View.GONE
        llSides.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return

        val inflater = LayoutInflater.from(this)
        entries.forEach { (chapter, side, route) ->
            val item = inflater.inflate(R.layout.item_story_chapter, llSides, false)
            bindSideItem(item, chapter, side, route)
            llSides.addView(item)
        }
    }

    private fun bindSideItem(item: View, chapter: StoryChapter, side: StorySideStory, route: StorySideRoute) {
        val tvIndex = item.findViewById<TextView>(R.id.tv_chapter_index)
        val tvTitle = item.findViewById<TextView>(R.id.tv_chapter_title)
        val tvMeta = item.findViewById<TextView>(R.id.tv_chapter_meta)
        val ivLock = item.findViewById<ImageView>(R.id.iv_chapter_lock)
        val tvBadge = item.findViewById<TextView>(R.id.tv_chapter_badge)
        val ivChevron = item.findViewById<ImageView>(R.id.iv_chapter_chevron)

        val unlocked = StoryProgressRepository.isSideStoryUnlocked(this, lineId, chapter.index, route.id)

        tvIndex.text = route.id.uppercase(Locale.ROOT)
        tvTitle.text = route.title

        if (unlocked) {
            tvMeta.text = getString(R.string.story_side_meta, chapter.chapterLabel, route.label)
            ivLock.visibility = View.GONE
            ivChevron.visibility = View.VISIBLE
            tvBadge.text = getString(R.string.story_side_unlocked)
            tvBadge.visibility = View.VISIBLE
            item.alpha = 1f
        } else {
            tvMeta.text = if (route.condition == "low") {
                getString(R.string.story_side_lock_low, chapter.chapterLabel, side.threshold)
            } else {
                getString(R.string.story_side_lock_high, chapter.chapterLabel, side.threshold)
            }
            ivLock.visibility = View.VISIBLE
            ivChevron.visibility = View.GONE
            tvBadge.visibility = View.GONE
            item.alpha = 0.5f
        }

        item.setOnClickListener {
            if (unlocked) {
                openSide(route)
            } else {
                Toast.makeText(this, tvMeta.text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun bindResumeCard() {
        val last = StoryProgressRepository.lastReadChapter(this, lineId)
        val chapter = chapters.firstOrNull { it.index == last }
        if (chapter == null || !StoryProgressRepository.isUnlocked(this, lineId, chapter)) {
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

    private fun openSide(route: StorySideRoute) {
        startActivity(
            Intent(this, StoryPlayerActivity::class.java)
                .putExtra(StoryPlayerActivity.EXTRA_LINE_ID, lineId)
                .putExtra(StoryPlayerActivity.EXTRA_SIDE_FILE, route.file)
        )
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
