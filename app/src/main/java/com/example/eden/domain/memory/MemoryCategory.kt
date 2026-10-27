package com.example.eden.domain.memory

/** 事实类型。identity 永远注入，其余走相关性检索。 */
enum class MemoryCategory(val value: String) {
    IDENTITY("identity"),
    PREFERENCE("preference"),
    EVENT("event"),
    RELATIONSHIP("relationship");

    companion object {
        fun of(value: String?): MemoryCategory =
            entries.firstOrNull { it.value == value } ?: EVENT
    }
}

/** 记忆主体，便于将来区分「关于用户」「关于她」「共同经历」。 */
object MemorySubject {
    const val USER = "user"
    const val CHARACTER = "miku"
    const val SHARED = "shared"
}

/** 抽取层输出的一条事实，尚未落库。 */
data class ExtractedFact(
    val category: MemoryCategory,
    val subject: String,
    val key: String,
    val content: String,
    val importance: Int
)
