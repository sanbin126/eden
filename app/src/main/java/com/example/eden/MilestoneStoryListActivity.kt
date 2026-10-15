package com.example.eden

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.eden.character.MilestoneStories
import com.example.eden.character.MilestoneStory

/**
 * 好感度小剧场列表：只列出「已解锁」的里程碑剧情，未解锁的完全不出现。
 * 已查看的以回看模式打开（按历史选择重放），未查看的点击进入正式剧情。
 * 一条都没解锁时展示空状态引导语。
 */
class MilestoneStoryListActivity : AppCompatActivity() {

    private lateinit var rvMilestone: RecyclerView
    private lateinit var btnBack: Button
    private lateinit var tvEmpty: TextView
    private val adapter = StoryAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_milestone_list)

        rvMilestone = findViewById(R.id.rv_milestone)
        btnBack = findViewById(R.id.btn_back)
        tvEmpty = findViewById(R.id.tv_milestone_empty)
        btnBack.setOnClickListener { finish() }

        rvMilestone.layoutManager = LinearLayoutManager(this)
        rvMilestone.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        // 看完某段剧情返回后刷新（红点消失、状态变为已查看）
        adapter.refresh()
    }

    private inner class StoryAdapter : RecyclerView.Adapter<StoryAdapter.StoryViewHolder>() {

        // 仅已解锁的里程碑；未解锁的不进入列表
        private var items: List<MilestoneStory> = emptyList()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StoryViewHolder =
            StoryViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_milestone_story, parent, false)
            )

        override fun onBindViewHolder(holder: StoryViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        fun refresh() {
            val unlocked = MilestoneManager.getUnlocked(this@MilestoneStoryListActivity)
            items = MilestoneStories.ALL.filter { unlocked.contains(it.milestone) }
            notifyDataSetChanged()
            val empty = items.isEmpty()
            tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
            rvMilestone.visibility = if (empty) View.GONE else View.VISIBLE
        }

        inner class StoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val root: View = itemView.findViewById(R.id.item_story_root)
            private val tvTitle: TextView = itemView.findViewById(R.id.tv_story_title)
            private val tvSubtitle: TextView = itemView.findViewById(R.id.tv_story_subtitle)
            private val viewDot: View = itemView.findViewById(R.id.view_story_dot)
            private val ivChevron: ImageView = itemView.findViewById(R.id.iv_story_chevron)

            fun bind(story: MilestoneStory) {
                val context = itemView.context
                val done = MilestoneManager.isCompleted(context, story.milestone)

                tvTitle.text = story.title
                root.alpha = 1f
                root.background = context.getDrawable(R.drawable.bg_game_card)

                if (done) {
                    tvSubtitle.text = "好感度 ${story.milestone} · 已查看"
                    viewDot.visibility = View.GONE
                } else {
                    tvSubtitle.text = "好感度 ${story.milestone} · 点击查看"
                    viewDot.visibility = View.VISIBLE
                }
                ivChevron.visibility = View.VISIBLE

                root.setOnClickListener {
                    context.startActivity(
                        Intent(context, MilestoneStoryActivity::class.java)
                            .putExtra(MilestoneStoryActivity.EXTRA_MILESTONE, story.milestone)
                            .putExtra(MilestoneStoryActivity.EXTRA_REPLAY, done)
                    )
                }
            }
        }
    }
}
