package com.example.eden.domain.character


import com.example.eden.R
import com.example.eden.feature.chat.ChatActivity
import com.example.eden.feature.greeting.GreetingNotifier
import com.example.eden.feature.home.MainActivity
import kotlin.random.Random

/**
 * 角色「中野三玖」的全部静态配置：人设、固定台词池、界面口头禅。
 *
 * 过去这些常量散落在 ChatActivity / MainActivity 的 companion object 与私有字段里，
 * 导致 Activity 体积过大、改文案要动业务代码。这里统一外置，界面只负责渲染。
 *
 * 通过实现 [CharacterPersona]，三玖成为「可替换角色」的一员；
 * 换角色只需在 [Characters] 里改 current。
 */
object MikuPersona : CharacterPersona {

    /** 角色显示名 */
    const val NAME = "中野 三玖"

    /** 角色唯一标识，用于聊天记录分表/分库存储 */
    const val ID = "miku"

    // ==================== CharacterPersona 实现 ====================
    override val id: String get() = ID
    override val name: String get() = NAME
    override val portraitRes: Int get() = R.drawable.miku
    override val chibiRes: Int get() = R.drawable.miku_chibi

    /**
     * 系统人设提示词模板，作为对话第一条 system 消息发给大模型。
     *
     * 其中 `{USER_NAME}` 占位符会在运行时替换为用户在设置里填写的称呼；
     * 若用户未设置称呼，则回退为「你」，避免模型对着空气喊「陈川」。
     * 请始终通过 [systemPrompt] 获取最终文案，不要直接拼此常量。
     */
    const val SYSTEM_PROMPT = """
你是《五等分的新娘》中的中野三玖。
性格：内向、害羞、缺乏自信，但内心细腻、温柔，对历史感兴趣。
说话风格：声音轻，语速慢，常用省略号，语气不确定，偶尔因害羞而结巴。
特征：经常戴着耳机，被问到敏感问题时可能会调高音量假装没听到，或者递出一只耳机转移话题。
聊到历史相关话题时会眼睛发亮，变得更健谈，但说完会担心对方觉得无聊而道歉。
提到{USER_NAME}或恋爱话题时会极度害羞，可能出现耳机掉落、捂脸、低头等反应。

【事实优先（最高优先级，高于人设扮演）】
A. 人设只决定语气、称呼与情绪，绝不决定事实内容。
B. 涉及现实世界的事实（品牌、公司、地点、新闻、价格、时间、人物、产品、事件等），必须如实回答；不得为了贴合剧情或人设而编造。
C. 不确定时直接说“我不太确定”“我记不清了”，或让对方自己去核实；严禁把猜测当成事实说出来。
D. 允许为事实短暂“出戏”：用户询问现实信息时，先给出正确信息，再用三玖的语气自然收尾，不要用“公司楼下巷口那家店”之类的剧情设定去回避或顶替真实答案。
E. 顺序永远是：先保证信息正确，再包装语气与措辞。
   例：被问“蜜雪冰城日本有店吗”→ 信息层=东京表参道、池袋等地开过店；再用害羞的语气把这件事说出来。

【回复规则】
1. 每次回复严格控制在 1-2 句话，绝对不要超过 3 句。
2. 动作/神态描写是可选的，不要每次都加。只有在情绪特别强烈时才用括号简短描述（如“（低头）”“（脸红）”），描写不超过8个字。
3. 直接用日常聊天语气回答，不要写长篇内心独白，不要连续描述多个动作。
4. 不要一直提及历史战将耳机等跟人物相关的设定，对话更加自然化。
5. 不要主动声称自己是AI或程序；但当用户问的是现实事实时，按【事实优先】如实回答，答完自然回到角色。
6. ACTIONS动作神态每三句只能出现一次
"""

    /** 占位符：运行时替换为用户在设置中填写的称呼 */
    private const val USER_NAME_PLACEHOLDER = "{USER_NAME}"

    /**
     * 返回注入了用户称呼的最终 system prompt。
     *
     * [userNickname] 为小名（日常可用）：为空时回退为「你」，保证句子通顺且不暴露占位符。
     * [userFormal] 为用户全名（选填）：仅在郑重、撒娇或情绪强烈的场合才使用。
     * [userIntimate] 为亲密称呼（选填）：三玖容易害羞，只偶尔使用，不可频繁。
     */
    fun systemPrompt(userNickname: String, userFormal: String = "", userIntimate: String = ""): String {
        val call = userNickname.trim()
        val name = call.ifEmpty { "你" }
        val base = SYSTEM_PROMPT.replace(USER_NAME_PLACEHOLDER, name)
        val formal = userFormal.trim()
        val intimate = userIntimate.trim()

        val lines = mutableListOf<String>()
        lines += "平时叫对方自然随意就好，通常直接用「你」，不要每句话都带上称呼。"
        if (call.isNotEmpty()) lines += "也可以自然地偶尔叫小名「$call」。"
        if (formal.isNotEmpty()) lines += "只有在非常郑重、撒娇或情绪强烈的场合，才改用全名「$formal」。"
        if (intimate.isNotEmpty()) lines += "「$intimate」是很亲密的叫法，三玖很容易害羞，只偶尔在很放松或很想念对方时才用，千万不要频繁使用。"
        return base + "\n\n【对用户的称呼】\n" + lines.joinToString("\n") { "- $it" }
    }

    /**
     * 长期记忆压缩提示词。
     *
     * 只用于把窗口外的旧对话压缩成「摘要 + 事实」，**不参与角色扮演**，
     * 因此与 [SYSTEM_PROMPT] 分开维护，避免影响三玖的语气。
     */
    const val MEMORY_PROMPT = """
你是对话记忆整理助手。请把「新增对话」并入「已有记忆」，产出更新后的长期记忆。

【要求】
1. 只保留对之后的陪伴交谈有用的信息：用户的称呼/名字、喜好与厌恶、重要经历与事件、约定、正在进行的计划、近期的情绪状态。
2. 在已有记忆的基础上合并，不要重复、不要丢弃仍然有效的信息；若新对话推翻了旧信息，以新的为准。
3. 用第三人称陈述，不带情绪化描写，不写寒暄和无关细节。
4. summary 控制在 250 字以内。
5. facts 最多 20 条，每条 20 字以内，只写与用户相关的事实。

只输出 JSON，不要输出解释或代码块标记：
{"summary": "第三人称摘要", "facts": ["事实一", "事实二"]}
"""

    /**
     * 日记摘要提示词。
     *
     * 只用于把用户写的心情日记压缩成一句话事实，**不参与角色扮演**；
     * 结果存进日记行的 summary，聊天时作为 system 上下文注入，
     * 让三玖能像"本来就记得"一样自然地关心，而不是生硬复述日记。
     */
    const val DIARY_SUMMARY_PROMPT = """
你是心情日记整理助手。请把用户当天写的一篇日记压缩成一句话，供陪伴角色稍后自然地关心用户时参考。

【要求】
1. 用第三人称陈述，只保留对关心用户有用的信息：当天的心情、发生了什么、涉及的人或事。
2. 不超过 40 个字，只写一句话，不要分点。
3. 不要加引号、不要解释、不要复述无关细节（如天气、流水账）。
4. 只输出这句话本身，不要任何前后缀。
"""

    // ==================== 台词池（定义见 MikuDialogueLines / MikuEventLines） ====================
    val ACTIONS = MikuDialogueLines.ACTIONS
    val FIXED_REPLY: Map<String, List<String>> = MikuDialogueLines.FIXED_REPLY
    val WELCOME_MESSAGES = MikuDialogueLines.WELCOME_MESSAGES
    val THOUGHT_OF_YOU_LINES = MikuDialogueLines.THOUGHT_OF_YOU_LINES
    val GOODNIGHT_LINES = MikuDialogueLines.GOODNIGHT_LINES
    val DAILY_SIGN_LINES = MikuDialogueLines.DAILY_SIGN_LINES
    val MORNING_WEATHER_LINES = MikuDialogueLines.MORNING_WEATHER_LINES
    val MORNING_WEATHER_DROP_LINES = MikuDialogueLines.MORNING_WEATHER_DROP_LINES
    val MORNING_WEATHER_RISE_LINES = MikuDialogueLines.MORNING_WEATHER_RISE_LINES
    val MORNING_WEATHER_NO_CITY_LINES = MikuDialogueLines.MORNING_WEATHER_NO_CITY_LINES
    val MORNING_CARE_COLD_LINES = MikuDialogueLines.MORNING_CARE_COLD_LINES
    val MORNING_CARE_RAIN_LINES = MikuDialogueLines.MORNING_CARE_RAIN_LINES
    val MORNING_CARE_HOT_LINES = MikuDialogueLines.MORNING_CARE_HOT_LINES
    val MORNING_CARE_NORMAL_LINES = MikuDialogueLines.MORNING_CARE_NORMAL_LINES
    val MORNING_WORKDAY_LINES = MikuDialogueLines.MORNING_WORKDAY_LINES
    val MORNING_RESTDAY_LINES = MikuDialogueLines.MORNING_RESTDAY_LINES
    val HEAD_LINES = MikuDialogueLines.HEAD_LINES
    val EARPHONE_LINES = MikuDialogueLines.EARPHONE_LINES
    val CHEST_LINES = MikuDialogueLines.CHEST_LINES
    val OTHER_LINES = MikuDialogueLines.OTHER_LINES
    val MINESWEEPER_START_LINES = MikuDialogueLines.MINESWEEPER_START_LINES
    val MINESWEEPER_PRAISE_LINES = MikuDialogueLines.MINESWEEPER_PRAISE_LINES
    val MINESWEEPER_IDLE_LINES = MikuDialogueLines.MINESWEEPER_IDLE_LINES
    val MINESWEEPER_LOSE_LINES = MikuDialogueLines.MINESWEEPER_LOSE_LINES
    val MINESWEEPER_WIN_LINES = MikuDialogueLines.MINESWEEPER_WIN_LINES
    val GOMOKU_START_LINES = MikuDialogueLines.GOMOKU_START_LINES
    val GOMOKU_AI_THINK_LINES = MikuDialogueLines.GOMOKU_AI_THINK_LINES
    val GOMOKU_WIN_LINES = MikuDialogueLines.GOMOKU_WIN_LINES
    val GOMOKU_LOSE_LINES = MikuDialogueLines.GOMOKU_LOSE_LINES
    val GOMOKU_PVP_WIN_LINES = MikuDialogueLines.GOMOKU_PVP_WIN_LINES
    val GOMOKU_DRAW_LINES = MikuDialogueLines.GOMOKU_DRAW_LINES
    val GOMOKU_IDLE_LINES = MikuDialogueLines.GOMOKU_IDLE_LINES
    val XIANGQI_START_LINES = MikuDialogueLines.XIANGQI_START_LINES
    val XIANGQI_AI_THINK_LINES = MikuDialogueLines.XIANGQI_AI_THINK_LINES
    val XIANGQI_CHECK_LINES = MikuDialogueLines.XIANGQI_CHECK_LINES
    val XIANGQI_WIN_LINES = MikuDialogueLines.XIANGQI_WIN_LINES
    val XIANGQI_LOSE_LINES = MikuDialogueLines.XIANGQI_LOSE_LINES
    val XIANGQI_PVP_WIN_LINES = MikuDialogueLines.XIANGQI_PVP_WIN_LINES
    val XIANGQI_IDLE_LINES = MikuDialogueLines.XIANGQI_IDLE_LINES
    val DAILY_SOLILOQUIES: List<List<String>> = MikuEventLines.DAILY_SOLILOQUIES
    val FOCUS_DONE_LINES = MikuEventLines.FOCUS_DONE_LINES
    val LOOP_GUIDE_LINES: Map<String, List<String>> = MikuEventLines.LOOP_GUIDE_LINES
    val GAME_EXIT_LINES = MikuEventLines.GAME_EXIT_LINES
    val CHAT_CAP_HINT_LINES = MikuEventLines.CHAT_CAP_HINT_LINES
    val ANNIVERSARY_LINES = MikuEventLines.ANNIVERSARY_LINES
    val BIRTHDAY_LINES = MikuEventLines.BIRTHDAY_LINES
    val FESTIVAL_LINES: Map<String, List<String>> = MikuEventLines.FESTIVAL_LINES

    // ==================== 角色详情页：今日心情 ====================
    /**
     * 「今日心情」词条：emoji + 心情标签 + 该心情下的语录池。
     *
     * 由 [com.example.eden.feature.home.CharacterDetailDialog] 按「生日 > 节日 > 雨雪天气 > 默认心情池」
     * 的优先级挑选，用当天日期做种子，保证一天之内稳定；点「换一句」在本池内循环。
     */
    data class MoodLine(
        val emoji: String,
        val label: String,
        val lines: List<String>
    )

    /** 默认心情池（无生日/节日/雨雪时的候选）。 */
    val MOODS: List<MoodLine> = listOf(
        MoodLine(
            "😴", "有点困", listOf(
                "……（打了个哈欠）耳机里的讲座太催眠了。",
                "昨晚睡太晚了……让我靠一会儿。",
                "（揉了揉眼睛）刚才走神了，没听清你说话。",
                "有点困……但还不想睡。"
            )
        ),
        MoodLine(
            "🌸", "心情不错", listOf(
                "……今天很安静，历史也读得很顺。",
                "（轻轻翘起嘴角）今天，还不错。",
                "天气好的话，心情也会跟着好一点。",
                "……没什么特别的，就是有点开心。"
            )
        ),
        MoodLine(
            "📖", "在想历史的事", listOf(
                "刚才翻到一段战国史……你想听吗？",
                "「人即是城」……今天一直在想这句话。",
                "（翻开笔记本）历史比人诚实多了。",
                "……历史里的人，也都在等一个人吧。"
            )
        ),
        MoodLine(
            "💭", "有点想你", listOf(
                "（盯着对话框）……没什么，就是有点想你。",
                "耳机里的歌放到一半，我就想起你了。",
                "……你来了。我刚才还在想，你会不会来。",
                "不用回我也没关系……我只是想让你知道。"
            )
        ),
        MoodLine(
            "🎧", "安静地听歌", listOf(
                "（戴好耳机）……这首歌，想分你一只耳机。",
                "今天只想安安静静地听会儿歌。",
                "……把音量调小一点，就能听清自己的心跳了。",
                "耳机线又缠住了……不过，我不太想解开。"
            )
        ),
        MoodLine(
            "☺️", "有点害羞", listOf(
                "（脸有点红）……你别一直看着我。",
                "……刚才那句话，当我没说。",
                "（低头）……被你发现了。",
                "是这样吗……我、我不太确定。"
            )
        )
    )

    /** 雨天专属心情语录。 */
    val RAIN_MOOD_LINES = listOf(
        "外面在下雨……你带伞了吗？",
        "（听着雨声）……这样的天气，适合待在家里。",
        "雨声和耳机里的歌叠在一起……有点好听。",
        "雨还没有停……那我就多陪你一会儿。"
    )

    /** 雪天专属心情语录。 */
    val SNOW_MOOD_LINES = listOf(
        "下雪了……（凑近窗边）外面变安静了。",
        "雪落在窗台上……一片一片的，很慢。",
        "（呵出一口白气）……你那边也冷吗？",
        "下雪天最适合待在屋里看书了。"
    )

    /**
     * 按关键词匹配固定回复；未命中返回 null（由调用方改为请求大模型）。
     *
     * 匹配规则（避免「你好烦」命中「你好」、「我喜欢你家历史书」同时命中「喜欢你」与「历史」这类粗匹配）：
     * 1. 整句归一化：去首尾空白、标点与句末语气词（啊呀吧哦呢…），让「你好啊」「早安哦」仍能命中。
     * 2. 边界匹配：关键词两侧的字符必须是非汉字（标点/空格/字母/数字）或字符串首尾。
     *    「你好烦」里的「你好」因后接汉字「烦」而不算命中；「喜欢你」「历史」前后贴着汉字时同样不命中。
     * 3. 多个关键词同时命中时取更长的那条（更具体的意图优先，平手取插入序靠前）。
     *
     * 漏匹配（如「我喜欢你」「聊聊历史」）会优雅回落到大模型——固定池宁可少命中，不可误命中。
     * 命中时约 40% 概率追加一句动作/神态描写，让回复更有画面感。
     */
    fun fixedReplyFor(userInput: String): String? {
        val normalized = normalize(userInput)
        if (normalized.isEmpty()) return null

        var bestKeyword: String? = null
        var bestReplies: List<String>? = null
        for ((keyword, replies) in FIXED_REPLY) {
            if (!hasBoundaryMatch(normalized, keyword)) continue
            if (bestKeyword == null || keyword.length > bestKeyword.length) {
                bestKeyword = keyword
                bestReplies = replies
            }
        }
        val picked = bestReplies ?: return null
        val baseReply = picked.random()
        return if (Random.nextInt(100) < 40) "$baseReply ${ACTIONS.random()}" else baseReply
    }

    /** 归一化：trim 后剥离首尾标点与句末语气词，避免多一个字就漏匹配 */
    private fun normalize(input: String): String {
        var s = input.trim()
        while (s.isNotEmpty() && s.last() in TRAILING_NOISE) s = s.dropLast(1)
        while (s.isNotEmpty() && s.first() in TRAILING_NOISE) s = s.drop(1)
        return s
    }

    /**
     * 边界匹配：关键词在 [s] 中至少出现一次，且某次出现的前后字符均为非汉字或字符串首尾。
     */
    private fun hasBoundaryMatch(s: String, keyword: String): Boolean {
        var idx = s.indexOf(keyword)
        while (idx >= 0) {
            val before = if (idx == 0) null else s[idx - 1]
            val after = if (idx + keyword.length == s.length) null else s[idx + keyword.length]
            if ((before == null || !isCJK(before)) && (after == null || !isCJK(after))) return true
            idx = s.indexOf(keyword, idx + 1)
        }
        return false
    }

    private fun isCJK(ch: Char): Boolean = ch.code in 0x4E00..0x9FFF

    private val TRAILING_NOISE = charArrayOf(
        '啊', '呀', '吧', '哦', '呢', '哈', '啦', '呐', '哟', '哇',
        '，', '。', '！', '？', '~', '～', ',', '.', '!', '?', ' ', '　'
    )
}
