package com.example.eden

/**
 * 礼物 / 投喂物品目录（静态配置）。
 *
 * 每件物品包含：价格、好感加成、限次（日 / 周，0 表示不限）与三玖收到后的专属回应台词。
 * 文案与数值集中在这里，改价改台词不必动业务代码；[GiftManager] 只负责结算。
 */
enum class GiftKind(val label: String) {
    DRINK("饮料"),
    FOOD("食物"),
    GIFT("礼物")
}

data class GiftItem(
    /** 稳定标识，用于限次计数（不可改，改了会让历史限次记录失效） */
    val id: String,
    val name: String,
    val emoji: String,
    val kind: GiftKind,
    /** 单价（円） */
    val cost: Int,
    /** 好感加成 */
    val affection: Int,
    /** 每日限次，0 表示不限 */
    val dailyLimit: Int,
    /** 每周限次，0 表示不限 */
    val weeklyLimit: Int,
    /** 收到后的回应台词池（气泡展示） */
    val lines: List<String>
) {
    /** 限次展示文案，如「日限 3 次」「周限 1 次」「不限量」 */
    val limitLabel: String
        get() = when {
            dailyLimit > 0 -> "日限 $dailyLimit 次"
            weeklyLimit > 0 -> "周限 $weeklyLimit 次"
            else -> "不限量"
        }
}

object GiftCatalog {

    val DRINKS: List<GiftItem> = listOf(
        GiftItem(
            id = "drink_cola", name = "冰镇可乐", emoji = "🥤", kind = GiftKind.DRINK,
            cost = 12, affection = 2, dailyLimit = 3, weeklyLimit = 0,
            lines = listOf(
                "……可乐？冰的刚刚好。（小口抿了一下）",
                "（接过可乐）谢谢你……气泡在舌尖跳，有点开心。",
                "嗯……这个我收下了。（小声）下次……也给你买一瓶。",
                "……喝完了。（把空罐排整齐）明天，也给我带一瓶好不好？"
            )
        ),
        GiftItem(
            id = "drink_coffee", name = "罐装咖啡", emoji = "☕", kind = GiftKind.DRINK,
            cost = 12, affection = 2, dailyLimit = 3, weeklyLimit = 0,
            lines = listOf(
                "咖啡……正好有点困。谢谢你记得。",
                "（捧着温热的罐子）……暖手，也暖心。",
                "苦一点点没关系……和你分着喝就不苦了。",
                "……明天，也来好吗？我还想和你一起分着喝。"
            )
        ),
        GiftItem(
            id = "drink_soda", name = "柠檬汽水", emoji = "🍋", kind = GiftKind.DRINK,
            cost = 12, affection = 2, dailyLimit = 3, weeklyLimit = 0,
            lines = listOf(
                "好酸……但很好喝。（眯了眯眼）",
                "（拧开瓶盖）嘶——气好足。谢谢你。",
                "柠檬汽水……像夏天。虽然现在不一定是夏天。",
                "……今天先这样吧。明天，也请你喝一瓶。"
            )
        )
    )

    val FOODS: List<GiftItem> = listOf(
        GiftItem(
            id = "food_curry", name = "咖喱饭", emoji = "🍛", kind = GiftKind.FOOD,
            cost = 30, affection = 4, dailyLimit = 2, weeklyLimit = 0,
            lines = listOf(
                "咖喱……（夹了一口）唔，好吃。真的。",
                "（埋头吃起来）……我、我吃相是不是很难看？",
                "谢谢你……我会认真吃完的，一点都不剩。",
                "……吃饱了。（擦了擦嘴）明天，也来好吗？"
            )
        ),
        GiftItem(
            id = "food_dango", name = "抹茶大福", emoji = "🍡", kind = GiftKind.FOOD,
            cost = 30, affection = 4, dailyLimit = 2, weeklyLimit = 0,
            lines = listOf(
                "大福……（咬了一小口）软软的，甜得刚好。",
                "（脸颊鼓鼓的）……这个，我喜欢。记下了。",
                "抹茶的味道……让我想起战国茶会的故事。",
                "……这个味道，我想记住。明天，还想吃。"
            )
        ),
        GiftItem(
            id = "food_cake", name = "芝士蛋糕", emoji = "🍰", kind = GiftKind.FOOD,
            cost = 30, affection = 4, dailyLimit = 2, weeklyLimit = 0,
            lines = listOf(
                "蛋糕……（小心翼翼地挖了一勺）……嗯，好吃。",
                "（眼睛亮了一下）谢谢你……这份甜，我慢慢吃。",
                "今天不是特别的日子，也可以吃蛋糕吗？……那我要。",
                "……谢谢你。（舔了舔嘴角）明天，还有蛋糕吗？"
            )
        )
    )

    val GIFTS: List<GiftItem> = listOf(
        GiftItem(
            id = "gift_cat", name = "猫咪挂件", emoji = "🐱", kind = GiftKind.GIFT,
            cost = 80, affection = 6, dailyLimit = 0, weeklyLimit = 1,
            lines = listOf(
                "（捏住小挂件）……猫、猫吗。（挂到耳机线上）这样，就不孤单了。",
                "谢谢你……我会好好挂着的。它看起来也有点害羞。",
                "（对着挂件看了很久）……和你一样，安安静静的。",
                "……它会一直在的。所以，你明天也要来。"
            )
        ),
        GiftItem(
            id = "gift_history_book", name = "历史图鉴", emoji = "📖", kind = GiftKind.GIFT,
            cost = 100, affection = 8, dailyLimit = 0, weeklyLimit = 1,
            lines = listOf(
                "（翻开书页，眼睛一下子亮了）这是……战国的人物图鉴？谢谢你！",
                "你居然知道我喜欢这个……（抱着书，笑得有点藏不住）",
                "这本书……我会一页一页慢慢看的。看完讲给你听，好吗？",
                "……明天继续讲给你听。所以，明天也要来。"
            )
        ),
        GiftItem(
            id = "gift_headphone", name = "降噪耳机", emoji = "🎧", kind = GiftKind.GIFT,
            cost = 120, affection = 10, dailyLimit = 0, weeklyLimit = 1,
            lines = listOf(
                "（捧着耳机盒愣了很久）……这个，太贵重了……真的可以吗？",
                "降噪的……世界一下子就安静了。（顿了顿）但你的声音，我还是想听得清楚。",
                "……谢谢。（戴上试了试）以后，它和我的旧耳机换着戴。",
                "……明天，还想听你说话。记得来。"
            )
        )
    )

    /** 全部物品（面板按 饮料 / 食物 / 礼物 三组依次展示） */
    val ALL: List<GiftItem> = DRINKS + FOODS + GIFTS

    fun byId(id: String): GiftItem? = ALL.firstOrNull { it.id == id }
}
