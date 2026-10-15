package com.example.eden

import android.content.Context
import com.example.eden.auth.SyncKeys
import java.io.File

/**
 * 用户「个人资料」的本地存储：头像 / 昵称 / 个性签名。
 *
 * 注意与 [NicknameManager] 的区别：
 * - [NicknameManager] 存的是「角色对用户的称呼」（小名 / 全名 / 亲密称呼），是给 AI 用的语料；
 * - 本类存的是「用户对外展示的身份」（微信式资料卡），是给人看的 UI 资料。
 *
 * 存储策略：
 * - 昵称 / 签名落在 `settings_prefs`（随账号分区），并随 [SyncKeys.SETTINGS] 文档同步到后端；
 * - 头像是一张本地图片（`filesDir/profile_avatar_u<id>.jpg`），体积大不走 JSON 同步，
 *   换账号时按分区后缀天然隔离。
 */
object ProfileManager {

    private const val PREFS_NAME = "settings_prefs"

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
            .ifEmpty { AuthManager.getNickname(context)?.trim().orEmpty() }
            .ifEmpty { DEFAULT_NICKNAME }

    fun setNickname(context: Context, name: String) {
        prefs(context).edit().putString(KEY_PROFILE_NAME, name.trim()).apply()
        SyncManager.notifyChanged(context, SyncKeys.SETTINGS)
    }

    /** 用户设置的个性签名原文，未设置返回空串。 */
    fun getSignature(context: Context): String =
        prefs(context).getString(KEY_SIGNATURE, "")?.trim().orEmpty()

    fun setSignature(context: Context, signature: String) {
        prefs(context).edit().putString(KEY_SIGNATURE, signature.trim()).apply()
        SyncManager.notifyChanged(context, SyncKeys.SETTINGS)
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

    private fun prefs(context: Context) =
        EdenStorage.prefs(context, PREFS_NAME)
}
