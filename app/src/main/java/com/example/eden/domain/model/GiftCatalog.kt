package com.example.eden.domain.model


import com.example.eden.domain.economy.EconomyManager
/**
 * 礼物 / 投喂物品目录（静态配置）。
 *
 * 每件物品包含：价格、好感加成、限次（日 / 周，0 表示不限）与三玖收到后的专属回应台词。
 * 文案与数值集中在这里，改价改台词不必动业务代码；[EconomyManager] 只负责结算。
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
        ),
        GiftItem(
            id = "drink_lime_cheese_tea", name = "青柠半熟芝士奶绿", emoji = "🧋", kind = GiftKind.DRINK,
            cost = 12, affection = 2, dailyLimit = 3, weeklyLimit = 0,
            lines = listOf(
                "（小声戳了戳杯壁）……芝士奶盖咸咸的，青柠又有点酸。……好喝。",
                "奶绿……颜色像战国画卷里的青苔。……我是不是说得太奇怪了。",
                "（吸了一大口，腮帮子鼓鼓的）……这个，能不能以后常来？",
                "……谢谢阿川。（把杯子捧在手里）甜的，我记住了。"
            )
        ),
        GiftItem(
            id = "drink_matcha_latte", name = "抹茶拿铁", emoji = "🍵", kind = GiftKind.DRINK,
            cost = 12, affection = 2, dailyLimit = 3, weeklyLimit = 0,
            lines = listOf(
                "抹茶……（闻了闻）很香的苦味。谢谢你。",
                "（喝了一口，眉头松开）……不苦。是温温的甜。",
                "抹茶配历史书，是很好的组合。……你要不要也看一页？",
                "……明天，也带一杯来好不好？（小声）就一杯。"
            )
        ),
        GiftItem(
            id = "drink_strawberry_milk", name = "草莓牛奶", emoji = "🍓", kind = GiftKind.DRINK,
            cost = 12, affection = 2, dailyLimit = 3, weeklyLimit = 0,
            lines = listOf(
                "草莓牛奶……小时候，只在电视里见过。",
                "（喝了一口，耳朵有点红）……甜的。有点不好意思。",
                "粉色……和我发色一样。（低头看了看杯子）",
                "……这个味道，我想留到明天慢慢想。"
            )
        ),
        GiftItem(
            id = "drink_honey_lemon", name = "蜂蜜柠檬水", emoji = "🍯", kind = GiftKind.DRINK,
            cost = 12, affection = 2, dailyLimit = 3, weeklyLimit = 0,
            lines = listOf(
                "蜂蜜柠檬……酸酸甜甜的。（抿了一小口）",
                "（抱着杯子）暖的……手心也暖了。",
                "你嗓子哑的时候，也喝这个吧。……我记下了。",
                "……明天，换我给你泡一杯。可以吗？"
            )
        ),
        GiftItem(
            id = "drink_oolong_tea", name = "乌龙茶", emoji = "🍶", kind = GiftKind.DRINK,
            cost = 12, affection = 2, dailyLimit = 3, weeklyLimit = 0,
            lines = listOf(
                "乌龙茶……不甜。（小口喝）这样刚好。",
                "（安静地喝完半杯）……和你一起喝，茶也不苦了。",
                "历史里，茶是慢慢喝的。……我们也可以慢一点。",
                "……明天，也一起慢慢喝一杯吧。"
            )
        ),
        GiftItem(
            id = "drink_hot_cocoa", name = "热可可", emoji = "🍫", kind = GiftKind.DRINK,
            cost = 12, affection = 2, dailyLimit = 3, weeklyLimit = 0,
            lines = listOf(
                "热可可……（吹了吹）有点烫。……但很好。",
                "（捧着杯子，脸埋在热气里）冬天的话，这个最好了。",
                "可可粉沾到嘴角了？……别、别看我。",
                "……明天要是也冷，就再买一杯吧。"
            )
        ),
        GiftItem(
            id = "drink_barley_tea", name = "焙茶", emoji = "🍂", kind = GiftKind.DRINK,
            cost = 12, affection = 2, dailyLimit = 3, weeklyLimit = 0,
            lines = listOf(
                "焙茶……有被炒过的香味。（闭眼闻了闻）",
                "（喝了一口）……很安静的味道。像傍晚。",
                "这个不用加糖就很好喝。……你也是这么觉得的吧。",
                "……明天傍晚，也一起喝吧。"
            )
        ),
        GiftItem(
            id = "drink_ramune", name = "波子汽水", emoji = "🫧", kind = GiftKind.DRINK,
            cost = 12, affection = 2, dailyLimit = 3, weeklyLimit = 0,
            lines = listOf(
                "波子汽水……（盯着玻璃珠）这个，怎么按下去？",
                "（噗的一声）……吓、吓了一跳。但气很足。",
                "小时候没怎么喝过这个。……原来是这样味道。",
                "……下次，你来帮我按弹珠，好吗？"
            )
        ),
        GiftItem(
            id = "drink_soy_milk", name = "豆浆", emoji = "🥛", kind = GiftKind.DRINK,
            cost = 12, affection = 2, dailyLimit = 3, weeklyLimit = 0,
            lines = listOf(
                "豆浆……（喝了一口）很温和。",
                "（捧着杯子）早上喝这个，好像一天都会顺利。",
                "你早饭吃了吗？……不要只给我买。",
                "……明天，我早点起来，也给你带一份。"
            )
        ),
        GiftItem(
            id = "drink_peach_soda", name = "白桃气泡水", emoji = "🍑", kind = GiftKind.DRINK,
            cost = 12, affection = 2, dailyLimit = 3, weeklyLimit = 0,
            lines = listOf(
                "白桃……（喝了一口，眼睛微微睁大）好香。",
                "（小口小口地喝）气泡在舌尖上……有点痒。",
                "桃子是夏天的味道。……虽然现在不一定。",
                "……这杯，我想喝慢一点。你在旁边的话。"
            )
        ),
        GiftItem(
            id = "drink_yuzu_tea", name = "柚子茶", emoji = "🍊", kind = GiftKind.DRINK,
            cost = 12, affection = 2, dailyLimit = 3, weeklyLimit = 0,
            lines = listOf(
                "柚子茶……（搅了搅）果肉沉在底下。",
                "（喝一口，呼出一口气）暖的。谢谢。",
                "苦一点的地方也很好喝……像历史故事。",
                "……明天，还想喝你买的这杯。"
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
        ),
        GiftItem(
            id = "food_takoyaki", name = "章鱼小丸子", emoji = "🐙", kind = GiftKind.FOOD,
            cost = 20, affection = 3, dailyLimit = 3, weeklyLimit = 0,
            lines = listOf(
                "（小声戳了戳盘子边角）刚出炉的……外皮脆脆的。",
                "（吹了吹，咬了一口）烫……但是，里面软软的。",
                "章鱼粒很大。……你没有偷吃吧？（看了你一眼）",
                "……下一颗，也分你一半。就一半。"
            )
        ),
        GiftItem(
            id = "food_omurice", name = "蛋包饭", emoji = "🍳", kind = GiftKind.FOOD,
            cost = 30, affection = 4, dailyLimit = 2, weeklyLimit = 0,
            lines = listOf(
                "蛋包饭……（用勺子轻轻划开）里面是番茄饭。",
                "（吃了一口）……好吃。比想象中还要好吃。",
                "上面可以写字的吧？……不用写，我知道是你做的。",
                "……都吃完了。明天，还想要。"
            )
        ),
        GiftItem(
            id = "food_miso_soup", name = "味增汤", emoji = "🥣", kind = GiftKind.FOOD,
            cost = 20, affection = 3, dailyLimit = 3, weeklyLimit = 0,
            lines = listOf(
                "味增汤……（小口喝）有豆腐和海带。",
                "（捧着碗）热的汤……会让心也静下来。",
                "味增放多了会咸，少了又淡。……你调得刚好。",
                "……明天，也煮一碗给我吧。"
            )
        ),
        GiftItem(
            id = "food_onigiri", name = "饭团", emoji = "🍙", kind = GiftKind.FOOD,
            cost = 20, affection = 3, dailyLimit = 3, weeklyLimit = 0,
            lines = listOf(
                "饭团……（双手接过）海苔是脆的。",
                "（咬了一口）米粒很结实。……好吃。",
                "战国行军时也吃饭团吧。……我在想奇怪的事。",
                "……这个，我也想学着做。教你，还是你教我？"
            )
        ),
        GiftItem(
            id = "food_ramen", name = "豚骨拉面", emoji = "🍜", kind = GiftKind.FOOD,
            cost = 30, affection = 4, dailyLimit = 2, weeklyLimit = 0,
            lines = listOf(
                "拉面……（吸了一口）汤是白的。（顿住）吃得响会不会失礼？",
                "（低头认真吃）……叉烧很软。",
                "（把汤喝了大半）……有点不好意思，但我真的喜欢。",
                "……下次，一起去店里吃吧。这次算你请的。"
            )
        ),
        GiftItem(
            id = "food_yakisoba", name = "炒面", emoji = "🍝", kind = GiftKind.FOOD,
            cost = 30, affection = 4, dailyLimit = 2, weeklyLimit = 0,
            lines = listOf(
                "炒面……（夹起一筷子）酱汁的味道很浓。",
                "（吃得很快，又突然停下）……我是不是吃太快了。",
                "铁板上的声音……很好听。",
                "……明天，还吃这个也可以吗？"
            )
        ),
        GiftItem(
            id = "food_gyoza", name = "煎饺", emoji = "🥟", kind = GiftKind.FOOD,
            cost = 30, affection = 4, dailyLimit = 2, weeklyLimit = 0,
            lines = listOf(
                "煎饺……底是脆的。（小心咬开）里面有汁。",
                "（吃得眼睛微眯）……六个，好像不够。",
                "你吃了吗？……不要只看着我吃。",
                "……下次多买一份。我们一起吃。"
            )
        ),
        GiftItem(
            id = "food_okonomiyaki", name = "大阪烧", emoji = "🥘", kind = GiftKind.FOOD,
            cost = 30, affection = 4, dailyLimit = 2, weeklyLimit = 0,
            lines = listOf(
                "大阪烧……（看着上面的木鱼花动）它在动。",
                "（切了一块）酱和蛋黄酱……味道很热闹。",
                "这种食物，好像适合很多人一起吃。……不过，两个人也很好。",
                "……明天，也两个人一起吃吧。"
            )
        ),
        GiftItem(
            id = "food_pudding", name = "焦糖布丁", emoji = "🍮", kind = GiftKind.FOOD,
            cost = 20, affection = 3, dailyLimit = 3, weeklyLimit = 0,
            lines = listOf(
                "布丁……（用勺子敲了敲）会晃。",
                "（吃了一口，沉默了两秒）……这个，很好吃。",
                "焦糖有点苦……正好。",
                "……最后一口留给你。……真的要吗？那、那就给你。"
            )
        ),
        GiftItem(
            id = "food_ice_cream", name = "香草冰淇淋", emoji = "🍦", kind = GiftKind.FOOD,
            cost = 20, affection = 3, dailyLimit = 3, weeklyLimit = 0,
            lines = listOf(
                "冰淇淋……（舔了一口）很冷。",
                "（缩了缩脖子）……但是甜的。",
                "会化掉，所以要快一点吃。……有点可惜。",
                "……下次，买两个。一人一个，不用抢。"
            )
        ),
        GiftItem(
            id = "food_apple_pie", name = "苹果派", emoji = "🥧", kind = GiftKind.FOOD,
            cost = 20, affection = 3, dailyLimit = 3, weeklyLimit = 0,
            lines = listOf(
                "苹果派……（咬了一口，酥皮掉了）……掉了。",
                "（低头捡起碎屑）里面是热的苹果。……好吃。",
                "肉桂的味道……有点像旧书店。",
                "……明天，也买一个吧。这次我来分。"
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
        ),
        GiftItem(
            id = "gift_bookmark", name = "战国书签", emoji = "🔖", kind = GiftKind.GIFT,
            cost = 60, affection = 5, dailyLimit = 0, weeklyLimit = 1,
            lines = listOf(
                "书签……（翻来覆去地看）上面是家纹吗？",
                "（夹进书里）这样，就不会忘记读到哪一页了。",
                "谢谢你……小小的东西，反而更难挑吧。",
                "……下次读到精彩的地方，我讲给你听。"
            )
        ),
        GiftItem(
            id = "gift_tea_set", name = "抹茶茶具", emoji = "🫖", kind = GiftKind.GIFT,
            cost = 100, affection = 8, dailyLimit = 0, weeklyLimit = 1,
            lines = listOf(
                "茶具……（小心地摆好）真的要给我吗？",
                "（试着点了一碗）……好像弄得太浓了。",
                "以后安静的时候，就泡一壶茶。……你也来。",
                "……我会好好洗、好好收着的。"
            )
        ),
        GiftItem(
            id = "gift_scarf", name = "针织围巾", emoji = "🧣", kind = GiftKind.GIFT,
            cost = 90, affection = 7, dailyLimit = 0, weeklyLimit = 1,
            lines = listOf(
                "围巾……（绕在脖子上，只露出半个脸）……暖。",
                "（把脸埋进去）……有太阳晒过的味道。",
                "冬天出门的时候，我会戴。……一定。",
                "……要不要，也分你一半？（往旁边挪了挪）"
            )
        ),
        GiftItem(
            id = "gift_plush", name = "小熊玩偶", emoji = "🧸", kind = GiftKind.GIFT,
            cost = 80, affection = 6, dailyLimit = 0, weeklyLimit = 1,
            lines = listOf(
                "玩偶……（抱在怀里，愣了愣）……软的。",
                "（捏了捏熊的耳朵）它看起来，比我还胆小。",
                "晚上放在枕边的话……大概就不怕黑了。",
                "……给它起名字？……那、那要好好想。"
            )
        ),
        GiftItem(
            id = "gift_music_box", name = "八音盒", emoji = "🎼", kind = GiftKind.GIFT,
            cost = 110, affection = 9, dailyLimit = 0, weeklyLimit = 1,
            lines = listOf(
                "八音盒……（上紧发条）……这是什么曲子？",
                "（安静地听完）……好像很久以前听过。",
                "转得越来越慢了。……但最后几个音还在。",
                "……以后睡前，我想听一遍。可以吗？"
            )
        ),
        GiftItem(
            id = "gift_star_lamp", name = "星空投影灯", emoji = "🌌", kind = GiftKind.GIFT,
            cost = 120, affection = 10, dailyLimit = 0, weeklyLimit = 1,
            lines = listOf(
                "投影灯……（关灯试了试）……房间里全是星星。",
                "（仰头看了很久，没说话）……",
                "这个，比历史书里的星图还要好看。",
                "……今晚，可以晚一点睡吗。"
            )
        ),
        GiftItem(
            id = "gift_letter_set", name = "信纸套装", emoji = "✉️", kind = GiftKind.GIFT,
            cost = 80, affection = 6, dailyLimit = 0, weeklyLimit = 1,
            lines = listOf(
                "信纸……（摸了摸纸的纹理）现在还有人写信吗？",
                "（想了想）……有些话，写下来好像比较容易。",
                "如果写了，你会读吗？……会的话，我就写。",
                "……第一封，先写给你。"
            )
        ),
        GiftItem(
            id = "gift_hairpin", name = "发夹", emoji = "🎀", kind = GiftKind.GIFT,
            cost = 70, affection = 5, dailyLimit = 0, weeklyLimit = 1,
            lines = listOf(
                "发夹……（别在耳边，照着窗玻璃看了看）……合适吗？",
                "（耳朵慢慢红了）……不、不要一直盯着看。",
                "明天，戴这个去学校。……你会看到吧。",
                "……谢谢你。我会小心的。"
            )
        ),
        GiftItem(
            id = "gift_history_map", name = "战国地图", emoji = "🗺️", kind = GiftKind.GIFT,
            cost = 120, affection = 10, dailyLimit = 0, weeklyLimit = 1,
            lines = listOf(
                "地图……（铺开，手指沿着路线走）这里，是尾张。",
                "（眼睛亮起来）河流、山道……原来是这样连着的。",
                "（看了很久，才想起抬头）……谢谢你。真的。",
                "……下次，陪我一起看吧。我讲给你听。"
            )
        ),
        GiftItem(
            id = "gift_hand_cream", name = "护手霜", emoji = "🧴", kind = GiftKind.GIFT,
            cost = 60, affection = 5, dailyLimit = 0, weeklyLimit = 1,
            lines = listOf(
                "护手霜……（挤了一点）是柑橘的味道。",
                "（慢慢地抹开）冬天手会裂。……你注意到了？",
                "……你的手，也伸出来一下。",
                "（很轻地抹上）……好了。不要擦掉。"
            )
        )
    )

    /** 全部物品（面板按 饮料 / 食物 / 礼物 三组依次展示） */
    val ALL: List<GiftItem> = DRINKS + FOODS + GIFTS

    fun byId(id: String): GiftItem? = ALL.firstOrNull { it.id == id }
}
