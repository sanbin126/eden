package com.example.eden

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 登录会话管理：保存/读取本地登录态（token 与用户信息）。
 *
 * 账号主体保存在后端，本地只持有「登录凭证 token」用于后续请求鉴权。
 * token 使用加密 SharedPreferences [EncryptedSharedPreferences] 落盘（密钥由 Android Keystore 托管），
 * 清除 App 数据 / 卸载重装会一并丢失，此时只需重新登录即可，无需重新注册。
 *
 * [token] 为内存缓存，供网络层拼接 Authorization 头使用，App 启动时通过 [init] 载入。
 */
object AuthManager {
    private const val PREFS_NAME = "auth_prefs"
    /** 加密方案不可用时使用的普通 SharedPreferences 名称（降级兜底） */
    private const val FALLBACK_PREFS_NAME = "auth_prefs_fallback"
    private const val KEY_TOKEN = "token"
    private const val KEY_EMAIL = "email"
    private const val KEY_NICKNAME = "nickname"
    private const val KEY_USER_ID = "user_id"

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    /** 当前登录凭证（内存缓存），未登录时为 null */
    @Volatile
    var token: String? = null
        private set

    /**
     * 当前登录账号 id（内存缓存），未登录或服务端未返回 id 时为 0。
     *
     * 是 [EdenStorage] 计算「账号分区后缀」的唯一依据，因此必须与 [token] 同步维护：
     * [init] 载入、[saveSession] 写入、[logout] 清零。
     */
    @Volatile
    var currentUserId: Long = 0L
        private set

    /** App 启动时调用一次，把持久化的 token 与账号 id 载入内存 */
    fun init(context: Context) {
        val p = prefs(context)
        token = p.getString(KEY_TOKEN, null)
        currentUserId = p.getLong(KEY_USER_ID, 0L)
    }

    fun saveSession(
        context: Context,
        token: String,
        email: String,
        nickname: String?,
        userId: Long?
    ) {
        prefs(context).edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_EMAIL, email)
            .putString(KEY_NICKNAME, nickname)
            .putLong(KEY_USER_ID, userId ?: 0L)
            .apply()
        this.token = token
        this.currentUserId = userId ?: 0L
    }

    fun logout(context: Context) {
        prefs(context).edit().clear().apply()
        token = null
        currentUserId = 0L
    }

    /** 读取持久化的账号 id；未登录或未记录返回 0。 */
    fun getUserId(context: Context): Long =
        prefs(context).getLong(KEY_USER_ID, 0L)

    fun isLoggedIn(context: Context): Boolean =
        !prefs(context).getString(KEY_TOKEN, null).isNullOrEmpty()

    fun getEmail(context: Context): String =
        prefs(context).getString(KEY_EMAIL, "") ?: ""

    fun getNickname(context: Context): String? =
        prefs(context).getString(KEY_NICKNAME, null)

    /** 统一的 Authorization 头取值，未登录返回空串 */
    fun bearerToken(): String = token?.let { "Bearer $it" } ?: ""

    /**
     * 获取存储实例：优先加密 SharedPreferences，若加密存储不可用（Keystore 异常、
     * 或落盘密文与当前 Keystore 密钥不匹配导致解密失败）则降级为普通 SharedPreferences，
     * 保证登录态读写在任何情况下都不崩溃。
     */
    private fun prefs(context: Context): SharedPreferences {
        cachedPrefs?.let { return it }
        synchronized(this) {
            cachedPrefs?.let { return it }
            val appContext = context.applicationContext
            val impl = openEncrypted(appContext)
                ?: run {
                    // 密文已损坏（解密失败）。此时 clear()/apply() 会抛 SecurityException，
                    // 先删掉损坏的落盘文件再重建一个空的加密存储即可自愈。
                    runCatching { appContext.deleteSharedPreferences(PREFS_NAME) }
                    openEncrypted(appContext)
                }
                ?: appContext.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
            cachedPrefs = impl
            return impl
        }
    }

    /**
     * 尝试打开加密存储，成功返回实例，失败返回 null。
     *
     * 注意：EncryptedSharedPreferences 是懒解密的，create() 阶段不会真正读取密文，
     * 因此这里必须主动触发一次全量读取（[SharedPreferences.getAll]）来提前探活，
     * 否则解密失败会推迟到后续 edit().apply() 时才在主线程抛出并导致闪退。
     */
    private fun openEncrypted(appContext: Context): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ).also { it.all }
    } catch (e: Exception) {
        null
    }
}
