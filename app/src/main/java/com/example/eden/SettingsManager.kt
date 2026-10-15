package com.example.eden

import android.content.Context
import com.example.eden.auth.SyncKeys
import org.json.JSONObject

/**
 * 通用设置开关的本地存储。
 * 目前包含「消息通知」「主动问候」两个开关。
 *
 * 角色对用户的「称呼」已抽到 [NicknameManager]，此处仅在其导出/导入中代为同步。
 */
object SettingsManager {
    private const val PREFS_NAME = "settings_prefs"
    private const val KEY_NOTIFICATION = "notification_enabled"
    private const val KEY_GREETING = "greeting_enabled"

    fun isNotificationEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFICATION, true)

    fun setNotificationEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFICATION, enabled).apply()
        SyncManager.notifyChanged(context, SyncKeys.SETTINGS)
    }

    fun isGreetingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GREETING, true)

    fun setGreetingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_GREETING, enabled).apply()
        SyncManager.notifyChanged(context, SyncKeys.SETTINGS)
    }

    // ==================== 与后端的业务数据同步（导出 / 导入） ====================

    /** 导出设置项为 JSON 对象字符串（供上传后端）。 */
    fun exportJson(context: Context): String = JSONObject().apply {
        put(KEY_NOTIFICATION, isNotificationEnabled(context))
        put(KEY_GREETING, isGreetingEnabled(context))
        put(NicknameManager.KEY_CALL, NicknameManager.getCallRaw(context))
        put(NicknameManager.KEY_FORMAL, NicknameManager.getFormal(context))
        put(NicknameManager.KEY_INTIMATE, NicknameManager.getIntimateRaw(context))
        // 个人资料（头像为本地文件，不参与同步）
        put(ProfileManager.KEY_PROFILE_NAME, ProfileManager.getNicknameRaw(context))
        put(ProfileManager.KEY_SIGNATURE, ProfileManager.getSignature(context))
    }.toString()

    /** 用后端拉取到的 JSON 覆盖本地设置（不做上传回调，避免回环）。 */
    fun importJson(context: Context, json: String) {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return
        prefs(context).edit()
            .putBoolean(KEY_NOTIFICATION, obj.optBoolean(KEY_NOTIFICATION, true))
            .putBoolean(KEY_GREETING, obj.optBoolean(KEY_GREETING, true))
            .putString(NicknameManager.KEY_CALL, obj.optString(NicknameManager.KEY_CALL, ""))
            .putString(NicknameManager.KEY_FORMAL, obj.optString(NicknameManager.KEY_FORMAL, ""))
            .putString(NicknameManager.KEY_INTIMATE, obj.optString(NicknameManager.KEY_INTIMATE, ""))
            .putString(
                ProfileManager.KEY_PROFILE_NAME,
                obj.optString(ProfileManager.KEY_PROFILE_NAME, "")
            )
            .putString(ProfileManager.KEY_SIGNATURE, obj.optString(ProfileManager.KEY_SIGNATURE, ""))
            .apply()
    }

    private fun prefs(context: Context) =
        EdenStorage.prefs(context, PREFS_NAME)
}
