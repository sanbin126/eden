package com.example.eden

import android.content.Context
import android.content.SharedPreferences
import com.example.eden.auth.SyncKeys
import org.json.JSONObject

object AffectionManager {
    private const val PREFS_NAME = "affection_prefs"
    private const val KEY_AFFECTION = "affection_value"

    /**
     * 获取当前好感度
     */
    fun getAffection(context: Context): Int {
        val prefs = getPrefs(context)
        return prefs.getInt(KEY_AFFECTION, 0)
    }

    /**
     * 增加好感度，返回 Pair(新值, 是否刚突破10的倍数)。
     * 好感度不设上限，可持续累积，每跨过 10 的倍数解锁一次里程碑。
     */
    fun increase(context: Context, amount: Int = 1): Pair<Int, Boolean> {
        val current = getAffection(context)
        val newValue = (current + amount).coerceAtLeast(0)
        getPrefs(context).edit().putInt(KEY_AFFECTION, newValue).apply()
        SyncManager.notifyChanged(context, SyncKeys.AFFECTION)

        val crossedMilestone = (current / 10) < (newValue / 10)
        return Pair(newValue, crossedMilestone)
    }

    /**
     * 减少好感度（保留备用）
     */
    fun decrease(context: Context, amount: Int = 1): Int {
        val current = getAffection(context)
        val newValue = (current - amount).coerceAtLeast(0)
        getPrefs(context).edit().putInt(KEY_AFFECTION, newValue).apply()
        SyncManager.notifyChanged(context, SyncKeys.AFFECTION)
        return newValue
    }

    // ==================== 与后端的业务数据同步（导出 / 导入） ====================

    /** 导出好感度为 JSON 对象字符串（供上传后端）。 */
    fun exportJson(context: Context): String =
        JSONObject().put(KEY_AFFECTION, getAffection(context)).toString()

    /** 用后端拉取到的 JSON 覆盖本地好感度（不做上传回调，避免回环）。 */
    fun importJson(context: Context, json: String) {
        val value = runCatching { JSONObject(json).optInt(KEY_AFFECTION, 0) }.getOrDefault(0)
        getPrefs(context).edit()
            .putInt(KEY_AFFECTION, value.coerceAtLeast(0))
            .apply()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return EdenStorage.prefs(context, PREFS_NAME)
    }
}