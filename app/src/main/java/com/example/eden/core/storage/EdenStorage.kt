package com.example.eden.core.storage


import com.example.eden.core.session.SessionManager
import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * 本地存储的「按账号分区」入口。
 *
 * 改造前，所有业务数据（Room 数据库 + 各业务 SharedPreferences）都只有设备级一份、
 * 不区分账号，切换账号靠「清空本地 → 重新拉取」硬切。这种结构下任何一环漏掉
 * （数据没纳入同步、切号没清干净、数据回来后界面内存缓存没刷新）都会出问题。
 *
 * 改造后，每个账号拥有独立分区：SharedPreferences 文件名与数据库文件名统一追加
 * `_u<userId>` 后缀，未登录时使用无后缀的公共区。切换账号只切换「当前读写的分区」，
 * 不再清空任何数据，从结构上消除账号间的数据串号。
 *
 * 设备级、不随账号变化的数据（登录态 auth_prefs、天气缓存 weather_prefs、
 * 本文件的全局标志）不走分区，仍用原始文件名。
 */
object EdenStorage {

    /** Room 数据库基础名（实际文件名形如 `eden.db` / `eden_u1.db`）。 */
    private const val DB_BASE = "eden"

    /** 记录设备级全局状态的存储（迁移标志等），不随账号分区。 */
    private const val GLOBAL_PREFS = "eden_global_prefs"

    /** 旧数据一次性迁移是否已完成的标志。 */
    private const val KEY_MIGRATED = "user_scope_migrated_v1"

    /**
     * 需要跟随账号分区的业务 SharedPreferences 基础名清单。
     * 与改造前 [SessionManager] 切号时清空的清单保持一致。
     */
    private val USER_SCOPED_PREFS = listOf(
        "affection_prefs", "wallet_prefs", "settings_prefs", "sign_in_prefs",
        "anniversary_prefs", "milestone_prefs", "greeting_prefs", "focus_prefs",
        "company_prefs", "sync_meta_prefs", "chat_history", "diary_store",
        "daily_sign_shown", "daily_soliloquy_shown", "daily_loop_prefs",
    )

    /** 当前账号分区后缀：已登录为 `_u<userId>`，未登录（userId <= 0）为空串。 */
    fun suffix(): String =
        SessionManager.currentUserId.takeIf { it > 0L }?.let { "_u$it" }.orEmpty()

    /** 把基础名字映射到当前账号分区的实际名字。 */
    fun name(base: String): String = base + suffix()

    /** 取当前账号分区下的 SharedPreferences。 */
    fun prefs(context: Context, base: String): SharedPreferences =
        context.applicationContext.getSharedPreferences(name(base), Context.MODE_PRIVATE)

    /** 当前账号分区的 Room 数据库文件名。 */
    fun dbName(): String = "$DB_BASE${suffix()}.db"

    /**
     * 旧数据一次性迁移：把改造前遗留的、未分区（无后缀）的本地数据改名到当前登录账号的分区，
     * 避免升级后「数据看着像丢了」。
     *
     * 只在首次执行、且当前已登录有账号时进行；未登录时不迁移（迁移标志此时仍未置位，
     * 故下次启动会重试），这样即使升级时没登录，等登录后也会完成迁移。
     */
    fun migrateLegacyToUserScope(context: Context) {
        val appContext = context.applicationContext
        val global = appContext.getSharedPreferences(GLOBAL_PREFS, Context.MODE_PRIVATE)
        if (global.getBoolean(KEY_MIGRATED, false)) return

        val uid = SessionManager.getUserId(appContext)
        if (uid <= 0L) return
        val suffix = "_u$uid"

        // 1) 业务 SharedPreferences：把 `xxx.xml` 改名为 `xxx_u<uid>.xml`
        val prefsDir = File(appContext.applicationInfo.dataDir, "shared_prefs")
        USER_SCOPED_PREFS.forEach { base ->
            val legacy = File(prefsDir, "$base.xml")
            val target = File(prefsDir, "$base$suffix.xml")
            if (legacy.exists() && !target.exists()) {
                runCatching { legacy.renameTo(target) }
            }
        }

        // 2) Room 数据库：把 `eden.db`（含 -wal/-shm/-journal）改名为 `eden_u<uid>.db`
        val dbDir = appContext.getDatabasePath("$DB_BASE.db").parentFile
        if (dbDir != null) {
            listOf("", "-wal", "-shm", "-journal").forEach { ext ->
                val legacy = File(dbDir, "$DB_BASE.db$ext")
                val target = File(dbDir, "$DB_BASE$suffix.db$ext")
                if (legacy.exists() && !target.exists()) {
                    runCatching { legacy.renameTo(target) }
                }
            }
        }

        global.edit().putBoolean(KEY_MIGRATED, true).apply()
    }
}
