package com.example.eden.domain.memory

/**
 * 规则抽取器：用关键词 + 正则从用户消息里提炼稳定事实。
 *
 * 阶段一只做规则，成本为零、准确率高；长尾事实留给后续 LLM 抽取。
 * 抽取结果按 `key` 去重，同一句话里重复命中只保留一条。
 */
object MemoryExtractor {

    /** 值里面不允许出现的分隔符与标点，避免把整句吞进来。 */
    private const val SEG = "[^\\s，。,.!！?？、；;：:“”\"'（）()\\[\\]【】]"

    private val NAME = Regex("(?:我叫|我的名字(?:是|叫)|叫我)\\s*($SEG{1,12})")
    private val AGE = Regex("我(?:今年|现在|已经)?\\s*(\\d{1,3})\\s*(?:岁|周岁)")
    private val LOCATION = Regex(
        "(?:我(?:现在|最近)?|现在)\\s*(?:搬家到|搬到了|搬到|住到了|住到|住在了|住在|住|家在)\\s*($SEG{2,15})"
    )
    private val WORK = Regex("我(?:现在|最近)?\\s*在\\s*($SEG{2,12})\\s*(?:工作|上班)")
    private val EXAM = Regex(
        "我(?:最近|现在|正在|在)?\\s*(?:准备|打算|要|想)?\\s*(考(?:研|证|公|编|试)?$SEG{0,15}|参加$SEG{1,15})"
    )
    private val LIKE = Regex("我(?:很|最|特别|超|挺|也|还)?喜欢\\s*($SEG{1,20})")
    private val DISLIKE = Regex("我(?:很|最|特别|超|挺)?(?:不喜欢|讨厌|受不了)\\s*($SEG{1,20})")

    /** 代词 / 泛指不能被记成稳定偏好。 */
    private val INVALID_TOPICS = setOf(
        "你", "你们", "他", "她", "它", "他们", "她们", "这个", "那个", "这些", "那些",
        "什么", "这样", "那样", "这种", "那种", "一切", "所有"
    )

    /** 句尾语气词与敷衍后缀，去掉后才是干净的主题。 */
    private val TRAILING = listOf(
        "就可以了", "就行", "就好", "可以", "吧", "啦", "了", "呀", "呢", "哦", "啊", "嘛"
    )

    /** 从一条用户消息中抽取所有可长期保留的事实。 */
    fun extract(text: String): List<ExtractedFact> {
        if (text.isBlank()) return emptyList()
        val facts = LinkedHashMap<String, ExtractedFact>()

        fun put(fact: ExtractedFact?) {
            if (fact != null && facts[fact.key] == null) facts[fact.key] = fact
        }

        put(NAME.find(text)?.groupValues?.get(1)?.let { raw ->
            clean(raw)?.let {
                fact(MemoryCategory.IDENTITY, "name", "用户叫$it", 5)
            }
        })

        put(AGE.find(text)?.groupValues?.get(1)?.let { raw ->
            raw.toIntOrNull()?.takeIf { it in 1..120 }?.let {
                fact(MemoryCategory.IDENTITY, "age", "用户今年${it}岁", 5)
            }
        })

        put(LOCATION.find(text)?.groupValues?.get(1)?.let { raw ->
            clean(raw)?.takeIf { it.length >= 2 }?.let {
                fact(MemoryCategory.IDENTITY, "location", "用户住在$it", 5)
            }
        })

        put(WORK.find(text)?.groupValues?.get(1)?.let { raw ->
            clean(raw)?.takeIf { it.length >= 2 }?.let {
                fact(MemoryCategory.IDENTITY, "job", "用户在${it}工作", 5)
            }
        })

        put(EXAM.find(text)?.groupValues?.get(1)?.let { raw ->
            clean(raw)?.takeIf { it.length >= 2 && !it.startsWith("考虑") }?.let {
                fact(MemoryCategory.EVENT, "exam", "用户在准备$it", 4)
            }
        })

        put(LIKE.find(text)?.groupValues?.get(1)?.let { raw ->
            clean(raw)?.let {
                fact(MemoryCategory.PREFERENCE, "fav_$it", "用户喜欢$it", 3)
            }
        })

        put(DISLIKE.find(text)?.groupValues?.get(1)?.let { raw ->
            clean(raw)?.let {
                fact(MemoryCategory.PREFERENCE, "dislike_$it", "用户不喜欢$it", 3)
            }
        })

        return facts.values.toList()
    }

    /** 用户是否在追问以前说过的事，命中时走全量召回。 */
    fun isExplicitRecall(text: String): Boolean =
        listOf("记得", "说过", "上次", "之前", "还记得", "以前说", "提过").any { text.contains(it) }

    private fun fact(category: MemoryCategory, key: String, content: String, importance: Int) =
        ExtractedFact(category, MemorySubject.USER, key, content, importance)

    /**
     * 清洗抽取到的值：去掉首尾标点、语气词与「就行 / 就好」这类后缀。
     * 清洗后为空或是代词则视为无效，返回 null。
     */
    private fun clean(raw: String): String? {
        var value = raw.trim().trim { it.isWhitespace() || it in "，。,.!！?？、；;：:—“”\"'()（）[]【】" }
        var changed = true
        while (changed && value.isNotEmpty()) {
            changed = false
            for (suffix in TRAILING) {
                if (value.length > suffix.length && value.endsWith(suffix)) {
                    value = value.removeSuffix(suffix).trimEnd()
                    changed = true
                }
            }
        }
        if (value.isEmpty() || value in INVALID_TOPICS) return null
        if (value.length > 20) value = value.take(20)
        return value
    }
}
