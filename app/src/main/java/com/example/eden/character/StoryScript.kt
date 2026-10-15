package com.example.eden.character

/**
 * 剧情板块的数据模型。
 *
 * ## 隔离设计（重要）
 * 剧情数据分两层命名空间，避免「切换角色」时内容互相串味：
 *
 * 1. **线路（lineId）** = [Characters.current] 的 id（当前为 `miku`）。一条线路 = 一个可攻略角色的
 *    完整剧本 + 独立进度。剧本目录 `assets/story/<lineId>/chapter_XX.json`，
 *    进度存储名 `story_prefs_<lineId>`（再经 EdenStorage 拼上账号后缀）。
 * 2. **剧中说话人（castId）** = 作品内角色 id：`ichika` / `nino` / `miku` / `yotsuba` /
 *    `itsuki` / `you`（玩家）/ 旁白由 [StoryStep.Narrate] 表达。立绘目录
 *    `assets/portrait/<castId>/`，跨章节、跨线路共用，不受切换角色影响。
 *
 * 因此将来新增线路（例如换成二乃线）时，只需新建 `assets/story/nino/` 目录，
 * 剧本、进度、立绘三者都不会与三玖线冲突。
 */

/** 剧中说话人（章节内可登场角色），与线路无关，跨章节共用一份定义。 */
data class StoryCast(
    val id: String,
    val name: String,
    /** 默认站位：left / center / right */
    val defaultSlot: String
)

/** 场景背景。当前用上下渐变色占位，将来可在 JSON 中补 `image` 字段换成真实背景图。 */
data class StoryBackground(
    val id: String,
    val label: String,
    val topColor: String,
    val bottomColor: String
)

/** 剧情步骤。 */
sealed class StoryStep {
    /** 角色台词。who = castId；slot 缺省用该 cast 的默认站位；face 为立绘差分（如 school_normal）。 */
    data class Say(
        val who: String,
        val text: String,
        val slot: String?,
        val face: String?
    ) : StoryStep()

    /**
     * 旁白，不显示名字牌。
     *
     * @param focus 仅当这段旁白「单独描写某个角色」时才填该角色 castId，此时展示其立绘；
     *   环境描写、多人动作等不聚焦单人的旁白一律留空，立绘会被隐藏。
     */
    data class Narrate(val text: String, val focus: String? = null) : StoryStep()

    /** 切换背景（淡入淡出）。 */
    data class ChangeBg(val backgroundId: String) : StoryStep()

    /** 选项卡。 */
    data class Choice(val options: List<StoryChoice>) : StoryStep()

    /** 章节结束。 */
    object End : StoryStep()
}

/**
 * 一个选项。
 *
 * @param affection 选中后的好感增减（可为负）
 * @param goto 选中后跳转的场景 id；为空则继续当前场景后续步骤
 */
data class StoryChoice(
    val text: String,
    val affection: Int,
    val goto: String?
)

/** 一个场景：一段连续演出，结束后走 [next]（为空则按章节内顺序进入下一场景）。 */
data class StoryScene(
    val id: String,
    val backgroundId: String,
    val next: String?,
    val steps: List<StoryStep>
)

/** 一章剧本。 */
data class StoryChapter(
    val id: String,
    val index: Int,
    /** 章节序号文案，如「第一章」 */
    val chapterLabel: String,
    val title: String,
    val synopsis: String,
    /** 解锁所需的前置章节序号（<=0 表示无前置） */
    val prevChapter: Int,
    /** 解锁所需好感下限 */
    val requiredAffection: Int,
    /** 首次通关奖励好感 */
    val rewardAffection: Int,
    val bgm: String,
    val casts: Map<String, StoryCast>,
    val backgrounds: Map<String, StoryBackground>,
    val scenes: List<StoryScene>
) {
    fun sceneById(id: String): StoryScene? = scenes.firstOrNull { it.id == id }

    fun sceneAfter(scene: StoryScene): StoryScene? {
        scene.next?.let { return sceneById(it) }
        val idx = scenes.indexOf(scene)
        return if (idx >= 0 && idx + 1 < scenes.size) scenes[idx + 1] else null
    }
}
