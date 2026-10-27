package com.example.eden.domain.character

/**
 * 好感度里程碑小剧场的数据模型。
 *
 * 每跨过 10 的倍数（10/20/30…）解锁一段专属短剧：三玖主动开口 → 抛出若干选项 →
 * 用户选择后得到固定回应 → 收尾。剧情为静态配置，交互状态机由
 * [com.example.eden.feature.story.MilestoneStoryActivity] 驱动。
 *
 * 全部文案集中在此，改剧情不用动业务代码（与 [MikuPersona] 外置文案同一思路）。
 */

/** 一个回复选项：[text] 是用户气泡里的文字，[reply] 是选择后三玖的固定回应。 */
data class StoryOption(val text: String, val reply: String)

/** 一轮对话：[mikuLine] 是三玖的引导句，之后弹出 [options] 供用户选择。 */
data class StoryRound(val mikuLine: String, val options: List<StoryOption>)

/**
 * 一段里程碑小剧场。
 *
 * 播放顺序：先逐句显示 [intro] → 依次进行每一 [rounds] → 最后逐句显示 [outro]。
 */
data class MilestoneStory(
    val milestone: Int,
    val title: String,
    val intro: List<String>,
    val rounds: List<StoryRound>,
    val outro: List<String>
)

/** 全部里程碑小剧场，按 [MilestoneStory.milestone] 升序。 */
object MilestoneStories {

    val ALL: List<MilestoneStory> = listOf(
        MilestoneStory(
            milestone = 10,
            title = "分你一只耳机",
            intro = listOf(
                "……那个。",
                "你要不要……听一下？"
            ),
            rounds = listOf(
                StoryRound(
                    mikuLine = "（递出一只耳机，视线没有对着你）",
                    options = listOf(
                        StoryOption(
                            text = "好啊。",
                            reply = "……嗯。（把另一只戴上）这首在讲关原之战……可能会有点吵。"
                        ),
                        StoryOption(
                            text = "听什么呀？",
                            reply = "……最近在听的历史讲座。你要是无聊，随时可以摘下来。"
                        ),
                        StoryOption(
                            text = "你自己听吧。",
                            reply = "……（手停在半空，慢慢收回去）嗯……没关系。"
                        )
                    )
                )
            ),
            outro = listOf("……谢谢你，愿意听我说这些废话。")
        ),
        MilestoneStory(
            milestone = 20,
            title = "她记住了",
            intro = listOf(
                "……今天。",
                "你上次说最近有点累。"
            ),
            rounds = listOf(
                StoryRound(
                    mikuLine = "（翻开笔记本又合上）……我记下来了。",
                    options = listOf(
                        StoryOption(
                            text = "你居然记得。",
                            reply = "……因为是你说的。（声音很轻）"
                        ),
                        StoryOption(
                            text = "其实还好啦。",
                            reply = "……骗人。你那天说话，声音比平时低。"
                        ),
                        StoryOption(
                            text = "谢谢你还记得。",
                            reply = "……不、不用谢。不是应该……是我想做的。"
                        )
                    )
                )
            ),
            outro = listOf("……所以今天，你可以少逞强一点。")
        )
    )

    /** 取某个里程碑对应的小剧场；没有配置的里程碑返回 null。 */
    fun forMilestone(milestone: Int): MilestoneStory? =
        ALL.firstOrNull { it.milestone == milestone }
}
