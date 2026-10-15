package com.example.eden.auth

/**
 * 业务数据同步相关数据模型（字段与后端 server/main.py 的 SyncDoc 保持一致）。
 *
 * 设计：后端用一张「按用户隔离」的通用文档表保存业务数据，
 * key 为业务标识（如 chat:miku / diary / signin），value 统一是 JSON 字符串。
 * 客户端本地（Room / SharedPreferences）只做缓存，真实数据以后端为准。
 */

/** 一条业务数据文档 */
data class SyncDoc(
    /** 业务标识，如 chat:miku / diary / signin / affection / anniversary / settings */
    val key: String,
    /** 该业务数据的 JSON 字符串 */
    val value: String,
    /** 最后更新时间戳（秒），后端可不返回 */
    val updatedAt: Long? = null
)

/** 拉取业务数据返回体 */
data class SyncData(
    val docs: List<SyncDoc> = emptyList()
)

/** 上传业务数据请求体 */
data class SyncPushRequest(
    val docs: List<SyncDoc>
)

/**
 * 业务数据 key 约定常量。
 *
 * 与后端 user_data 表的 data_key 一一对应，增删业务时两端需同步维护。
 */
object SyncKeys {
    const val DIARY = "diary"
    const val SIGN_IN = "signin"
    const val AFFECTION = "affection"
    const val ANNIVERSARY = "anniversary"
    const val SETTINGS = "settings"
    /** 货币「円」钱包余额（每日/每周配额等本机状态不参与同步） */
    const val WALLET = "wallet"

    /** 聊天类文档的 key 前缀，完整 key 形如 `chat:miku` */
    const val CHAT_PREFIX = "chat:"

    /** 某个角色的聊天记录（含长期记忆）在服务端的文档 key */
    fun chat(characterId: String): String = "$CHAT_PREFIX$characterId"
}
