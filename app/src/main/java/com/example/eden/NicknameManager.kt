package com.example.eden

import android.content.Context
import com.example.eden.auth.SyncKeys
import org.json.JSONArray

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
object NicknameManager {
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
        SyncManager.notifyChanged(context, SyncKeys.SETTINGS)
    }

    /** 用户自定义：同时写入小名与全名（均选填，可只填其一）。 */
    fun setCustom(context: Context, call: String, formal: String) {
        prefs(context).edit()
            .putString(KEY_CALL, call.trim())
            .putString(KEY_FORMAL, formal.trim())
            .putBoolean(KEY_USER_LOCKED, true)
            .apply()
        SyncManager.notifyChanged(context, SyncKeys.SETTINGS)
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
        SyncManager.notifyChanged(context, SyncKeys.SETTINGS)
        return autoEquip
    }

    private fun prefs(context: Context) =
        EdenStorage.prefs(context, PREFS_NAME)
}
