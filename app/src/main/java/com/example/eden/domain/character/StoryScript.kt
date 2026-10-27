package com.example.eden.domain.character


import com.example.eden.core.storage.EdenStorage
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

/**
 * 场景背景。
 *
 * [image] 为背景图的 assets 相对路径（如 `story_bg/bg_nakano_living_day.png`，跨线路共用）；
 * 缺失或加载失败时回退到 [topColor] → [bottomColor] 的上下渐变。
 */
data class StoryBackground(
    val id: String,
    val label: String,
    val topColor: String,
    val bottomColor: String,
    val image: String? = null
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

/**
 * 章末支线（番外）条目。
 *
 * @param id 支线标识（`a` / `b`）
 * @param label 展示用短标签，如「支线A」
 * @param title 支线标题
 * @param file 剧本文件名（不含扩展名），位于 `assets/story/<lineId>/<file>.json`
 * @param condition 解锁走向：`high` 表示本章好感度 ≥ 阈值，`low` 表示 < 阈值
 */
data class StorySideRoute(
    val id: String,
    val label: String,
    val title: String,
    val file: String,
    val condition: String
)

/** 一章的章末支线配置：达到 [threshold] 走 [routes] 中 condition=high 的一条，否则走 low 的一条。 */
data class StorySideStory(
    val threshold: Int,
    val routes: List<StorySideRoute>
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
    val scenes: List<StoryScene>,
    /** 章末支线配置；没有番外的章节为 null。 */
    val sideStory: StorySideStory? = null
) {
    fun sceneById(id: String): StoryScene? = scenes.firstOrNull { it.id == id }

    /** 本章好感度为 [affection] 时应当解锁的支线；没有番外返回 null。 */
    fun sideRouteFor(affection: Int): StorySideRoute? {
        val side = sideStory ?: return null
        val target = if (affection >= side.threshold) "high" else "low"
        return side.routes.firstOrNull { it.condition == target }
            ?: side.routes.firstOrNull { it.condition == "high" }
    }

    fun sceneAfter(scene: StoryScene): StoryScene? {
        scene.next?.let { return sceneById(it) }
        val idx = scenes.indexOf(scene)
        return if (idx >= 0 && idx + 1 < scenes.size) scenes[idx + 1] else null
    }
}
