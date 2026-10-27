package com.example.eden.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.eden.core.session.SessionManager
import com.example.eden.core.session.SyncKeys
import com.example.eden.core.storage.EdenStorage
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 用户侧资料的唯一入口：角色对用户的称呼、展示资料（昵称/签名/头像）、通用设置开关。
 *
 * 原先的 NicknameManager / ProfileManager / SettingsManager 合并于此，
 * 三者共用 `settings_prefs` 并作为同一个同步文档上传，本就是一个聚合。
 */
object ProfileRepository {

/**
 * 角色对用户「称呼」的唯一取用口。
 *
 * 设计要点（解决「用户自定义」与「剧情解锁」的冲突）：
 * 1. 称呼库 + 唯一装备位：所有可用称呼（预设 + 剧情解锁）汇总为称呼库，
 *    但同一时刻只有一个「当前称呼」，避免多个来源互相抢夺。
 * 2. 用户锁：用户在设置里任何一次手动选择都会置位 [KEY_USER_LOCKED]，
 *    此后剧情解锁只会把新称呼登记入称呼库，绝不静默覆盖用户的选择。
 * 3. 去重：称呼库按文本去重，重复解锁不会产生重复项。
 *
 * 三槽：
 * - 小名（call，日常使用，可为空）
 * - 全名（formal，仅郑重/撒娇/情绪强烈时使用）
 * - 亲密称呼（intimate，很亲密的叫法，三玖害羞，只偶尔使用）
 */

    // ==================== 来自 ProfileRepository ====================
    private const val PREFS_NAME = "settings_prefs"

    /** 小名（日常称呼）主键。沿用旧键名，保证历史数据与后端同步继续兼容。 */
    internal const val KEY_CALL = "user_nickname"

    /** 全名（郑重场合才用）。 */
    internal const val KEY_FORMAL = "nickname_formal"

    /** 亲密称呼（很亲密的叫法，仅偶尔使用）。 */
    internal const val KEY_INTIMATE = "nickname_intimate"

    /** 用户是否手动选过称呼（手动选择后永久锁定）。 */
    private const val KEY_USER_LOCKED = "nickname_user_locked"

    /** 剧情解锁的称呼库（JSON 数组字符串）。 */
    private const val KEY_UNLOCKED = "nickname_unlocked"

    /** 亲密称呼预设池（设置页「亲密称呼」的可选项）。 */
    val PRESETS = listOf("宝宝", "亲亲", "亲爱的", "小猫")

    /** 兜底称呼：用户从未设置时使用。 */
    const val DEFAULT_CALL = "你"

    /** 用户设置的小名（日常称呼）。未设置返回空串，调用方自行兜底。 */
    fun getCallRaw(context: Context): String =
        prefs(context).getString(KEY_CALL, "")?.trim().orEmpty()

    /** 用户设置的小名，带兜底。 */
    fun getCall(context: Context): String = getCallRaw(context).ifEmpty { DEFAULT_CALL }

    /** 用户设置的全名（郑重场合）。未设置返回空串。 */
    fun getFormal(context: Context): String =
        prefs(context).getString(KEY_FORMAL, "")?.trim().orEmpty()

    /** 用户设置的亲密称呼（很亲密的叫法）。未设置返回空串。 */
    fun getIntimateRaw(context: Context): String =
        prefs(context).getString(KEY_INTIMATE, "")?.trim().orEmpty()

    /** 用户是否已经手动选择过称呼。 */
    fun isUserLocked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_USER_LOCKED, false)

    /** 剧情解锁的称呼列表。 */
    fun unlockedCalls(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_UNLOCKED, "")?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).trim().ifEmpty { null } }
    }

    /** 预设 + 已解锁称呼（去重）的下拉选项。 */
    fun allOptions(context: Context): List<String> =
        (PRESETS + unlockedCalls(context)).distinct()

    /** 用户在「亲密称呼」里手动选择某个称呼：写入亲密称呼槽并永久锁定。 */
    fun select(context: Context, call: String) {
        prefs(context).edit()
            .putString(KEY_INTIMATE, call.trim())
            .putBoolean(KEY_USER_LOCKED, true)
            .apply()
        SessionManager.notifyChanged(context, SyncKeys.SETTINGS)
    }

    /** 用户自定义：同时写入小名与全名（均选填，可只填其一）。 */
    fun setCustom(context: Context, call: String, formal: String) {
        prefs(context).edit()
            .putString(KEY_CALL, call.trim())
            .putString(KEY_FORMAL, formal.trim())
            .putBoolean(KEY_USER_LOCKED, true)
            .apply()
        SessionManager.notifyChanged(context, SyncKeys.SETTINGS)
    }

    /**
     * 剧情 / 对话解锁新称呼。无论用户是否锁定都会登记入称呼库；
     * 仅当用户从未手动选过（!isUserLocked）时才自动生效为亲密称呼。
     *
     * @return true 表示已自动生效；false 表示仅入库（用户已锁定，需其自行选择）。
     */
    fun unlock(context: Context, call: String): Boolean {
        val name = call.trim()
        if (name.isEmpty()) return false
        val editor = prefs(context).edit()
        val existing = unlockedCalls(context)
        if (name !in existing) {
            val arr = JSONArray()
            (existing + name).distinct().forEach { arr.put(it) }
            editor.putString(KEY_UNLOCKED, arr.toString())
        }
        val autoEquip = !isUserLocked(context)
        if (autoEquip) editor.putString(KEY_INTIMATE, name)
        editor.apply()
        SessionManager.notifyChanged(context, SyncKeys.SETTINGS)
        return autoEquip
    }

    private fun prefs(context: Context) =
        EdenStorage.prefs(context, PREFS_NAME)

/**
 * 用户「个人资料」的本地存储：头像 / 昵称 / 个性签名。
 *
 * 注意与 [ProfileRepository] 的区别：
 * - [ProfileRepository] 存的是「角色对用户的称呼」（小名 / 全名 / 亲密称呼），是给 AI 用的语料；
 * - 本类存的是「用户对外展示的身份」（微信式资料卡），是给人看的 UI 资料。
 *
 * 存储策略：
 * - 昵称 / 签名落在 `settings_prefs`（随账号分区），并随 [SyncKeys.SETTINGS] 文档同步到后端；
 * - 头像是一张本地图片（`filesDir/profile_avatar_u<id>.jpg`），体积大不走 JSON 同步，
 *   换账号时按分区后缀天然隔离。
 */

    // ==================== 来自 ProfileRepository ====================

    /** 展示昵称。 */
    internal const val KEY_PROFILE_NAME = "profile_nickname"

    /** 个性签名。 */
    internal const val KEY_SIGNATURE = "profile_signature"

    private const val AVATAR_BASE = "profile_avatar"

    /** 昵称兜底：用户未设置且账号也没有昵称时使用。 */
    const val DEFAULT_NICKNAME = "Eden 用户"

    /** 用户设置的展示昵称原文，未设置返回空串。 */
    fun getNicknameRaw(context: Context): String =
        prefs(context).getString(KEY_PROFILE_NAME, "")?.trim().orEmpty()

    /**
     * 界面展示用的昵称：优先资料昵称，其次注册时的账号昵称，最后兜底。
     */
    fun getDisplayNickname(context: Context): String =
        getNicknameRaw(context)
            .ifEmpty { SessionManager.getNickname(context)?.trim().orEmpty() }
            .ifEmpty { DEFAULT_NICKNAME }

    fun setNickname(context: Context, name: String) {
        prefs(context).edit().putString(KEY_PROFILE_NAME, name.trim()).apply()
        SessionManager.notifyChanged(context, SyncKeys.SETTINGS)
    }

    /** 用户设置的个性签名原文，未设置返回空串。 */
    fun getSignature(context: Context): String =
        prefs(context).getString(KEY_SIGNATURE, "")?.trim().orEmpty()

    fun setSignature(context: Context, signature: String) {
        prefs(context).edit().putString(KEY_SIGNATURE, signature.trim()).apply()
        SessionManager.notifyChanged(context, SyncKeys.SETTINGS)
    }

    // ==================== 头像文件 ====================

    /** 当前账号的头像文件（无论是否已存在）。 */
    fun avatarFile(context: Context): File =
        File(context.applicationContext.filesDir, "$AVATAR_BASE${EdenStorage.suffix()}.jpg")

    /** 当前账号的头像文件，不存在则返回 null。 */
    fun getAvatarFile(context: Context): File? =
        avatarFile(context).takeIf { it.exists() && it.length() > 0 }

    /** 把裁剪产物复制为当前账号的头像，成功返回 true。 */
    fun saveAvatar(context: Context, source: File): Boolean {
        if (!source.exists() || source.length() <= 0) return false
        return runCatching { source.copyTo(avatarFile(context), overwrite = true) }.isSuccess
    }

    /**
     * 解码当前账号头像（最长边约 [maxSize] px），无头像或解码失败返回 null。
     * 设置页与聊天页共用这一条解码路径，保证两处显示的是同一张图。
     */
    fun loadAvatarBitmap(context: Context, maxSize: Int = 256): Bitmap? {
        val file = getAvatarFile(context) ?: return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / (sample * 2) >= maxSize) sample *= 2

            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        }.getOrNull()
    }

/**
 * 通用设置开关的本地存储。
 * 目前包含「消息通知」「主动问候」两个开关。
 *
 * 角色对用户的「称呼」已抽到 [ProfileRepository]，此处仅在其导出/导入中代为同步。
 */

    // ==================== 来自 ProfileRepository ====================
    private const val KEY_NOTIFICATION = "notification_enabled"
    private const val KEY_GREETING = "greeting_enabled"

    fun isNotificationEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFICATION, true)

    fun setNotificationEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFICATION, enabled).apply()
        SessionManager.notifyChanged(context, SyncKeys.SETTINGS)
    }

    fun isGreetingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GREETING, true)

    fun setGreetingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_GREETING, enabled).apply()
        SessionManager.notifyChanged(context, SyncKeys.SETTINGS)
    }

    // ==================== 与后端的业务数据同步（导出 / 导入） ====================

    /** 导出设置项为 JSON 对象字符串（供上传后端）。 */
    fun exportJson(context: Context): String = JSONObject().apply {
        put(KEY_NOTIFICATION, isNotificationEnabled(context))
        put(KEY_GREETING, isGreetingEnabled(context))
        put(ProfileRepository.KEY_CALL, ProfileRepository.getCallRaw(context))
        put(ProfileRepository.KEY_FORMAL, ProfileRepository.getFormal(context))
        put(ProfileRepository.KEY_INTIMATE, ProfileRepository.getIntimateRaw(context))
        // 个人资料（头像为本地文件，不参与同步）
        put(ProfileRepository.KEY_PROFILE_NAME, ProfileRepository.getNicknameRaw(context))
        put(ProfileRepository.KEY_SIGNATURE, ProfileRepository.getSignature(context))
    }.toString()

    /** 用后端拉取到的 JSON 覆盖本地设置（不做上传回调，避免回环）。 */
    fun importJson(context: Context, json: String) {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return
        prefs(context).edit()
            .putBoolean(KEY_NOTIFICATION, obj.optBoolean(KEY_NOTIFICATION, true))
            .putBoolean(KEY_GREETING, obj.optBoolean(KEY_GREETING, true))
            .putString(ProfileRepository.KEY_CALL, obj.optString(ProfileRepository.KEY_CALL, ""))
            .putString(ProfileRepository.KEY_FORMAL, obj.optString(ProfileRepository.KEY_FORMAL, ""))
            .putString(ProfileRepository.KEY_INTIMATE, obj.optString(ProfileRepository.KEY_INTIMATE, ""))
            .putString(
                ProfileRepository.KEY_PROFILE_NAME,
                obj.optString(ProfileRepository.KEY_PROFILE_NAME, "")
            )
            .putString(ProfileRepository.KEY_SIGNATURE, obj.optString(ProfileRepository.KEY_SIGNATURE, ""))
            .apply()
    }
}
