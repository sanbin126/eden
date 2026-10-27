package com.example.eden.core.session

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.eden.core.storage.EdenStorage
import com.example.eden.data.repository.ChatRepository
import com.example.eden.data.repository.DiaryRepository
import com.example.eden.data.repository.ProfileRepository
import com.example.eden.domain.economy.EconomyManager
import com.example.eden.domain.model.ChatSession
import com.example.eden.feature.daily.DailyLoopManager
import com.example.eden.feature.signin.SignInRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 会话的唯一入口：登录态（token / userId）与账号级数据同步。
 *
 * 原先的 SessionManager / SessionManager 合并于此——同步永远依附于当前会话，
 * 会话切换与同步重置必须原子地一起发生。
 */
object SessionManager {

/**
 * 登录会话管理：保存/读取本地登录态（token 与用户信息）。
 *
 * 账号主体保存在后端，本地只持有「登录凭证 token」用于后续请求鉴权。
 * token 使用加密 SharedPreferences [EncryptedSharedPreferences] 落盘（密钥由 Android Keystore 托管），
 * 清除 App 数据 / 卸载重装会一并丢失，此时只需重新登录即可，无需重新注册。
 *
 * [token] 为内存缓存，供网络层拼接 Authorization 头使用，App 启动时通过 [init] 载入。
 */

    // ==================== 来自 SessionManager ====================
    private const val AUTH_PREFS_NAME = "auth_prefs"
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
        val p = authPrefs(context)
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
        authPrefs(context).edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_EMAIL, email)
            .putString(KEY_NICKNAME, nickname)
            .putLong(KEY_USER_ID, userId ?: 0L)
            .apply()
        this.token = token
        this.currentUserId = userId ?: 0L
    }

    fun logout(context: Context) {
        authPrefs(context).edit().clear().apply()
        token = null
        currentUserId = 0L
    }

    /** 读取持久化的账号 id；未登录或未记录返回 0。 */
    fun getUserId(context: Context): Long =
        authPrefs(context).getLong(KEY_USER_ID, 0L)

    fun isLoggedIn(context: Context): Boolean =
        !authPrefs(context).getString(KEY_TOKEN, null).isNullOrEmpty()

    fun getEmail(context: Context): String =
        authPrefs(context).getString(KEY_EMAIL, "") ?: ""

    fun getNickname(context: Context): String? =
        authPrefs(context).getString(KEY_NICKNAME, null)

    /** 统一的 Authorization 头取值，未登录返回空串 */
    fun bearerToken(): String = token?.let { "Bearer $it" } ?: ""

    /**
     * 获取存储实例：优先加密 SharedPreferences，若加密存储不可用（Keystore 异常、
     * 或落盘密文与当前 Keystore 密钥不匹配导致解密失败）则降级为普通 SharedPreferences，
     * 保证登录态读写在任何情况下都不崩溃。
     */
    private fun authPrefs(context: Context): SharedPreferences {
        cachedPrefs?.let { return it }
        synchronized(this) {
            cachedPrefs?.let { return it }
            val appContext = context.applicationContext
            val impl = openEncrypted(appContext)
                ?: run {
                    // 密文已损坏（解密失败）。此时 clear()/apply() 会抛 SecurityException，
                    // 先删掉损坏的落盘文件再重建一个空的加密存储即可自愈。
                    runCatching { appContext.deleteSharedPreferences(AUTH_PREFS_NAME) }
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
            AUTH_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ).also { it.all }
    } catch (e: Exception) {
        null
    }

/**
 * 业务数据同步总调度。
 *
 * 同步策略为「最后写入者胜出」（LWW，Last-Write-Wins）：本地与后端各记一份
 * 修改时间戳，拉取时谁的时间更晚就采信谁，而不是无条件用后端覆盖本地。
 * 这样既能拉回其他设备 / 重装后的数据，又不会把本地较新的改动（例如刚签的到、
 * 刚调过的好感度）冲掉。注意这里按「时间」取最新，而不是按数值取最大——
 * 好感度等数据用户会主动调小，取最大会得到错误结果。
 *
 * 例外：签到是「只增不减」的日期集合，两端取并集永远比按时间取舍更安全，
 * 因此它单独走并集合并（时间戳取较新者）。
 *
 * - [syncOnLogin]：登录成功后拉取该用户全部业务数据，按 LWW 合并进本地缓存。
 * - [notifyChanged]：本地数据变更后记录修改时间，并防抖合并成一次批量上传。
 *
 * key ↔ 本地存储的映射集中在本文件的 [exportDoc] / [importDoc]，
 * 各业务模块只负责提供自己的 exportJson / importJson（这些方法内部不再回调 [notifyChanged]，
 * 避免「导入 → 又触发上传」的回环）。
 */

    // ==================== 来自 SessionManager ====================
    /** 本地变更合并窗口：短时间内的多次改动合并成一次上传，避免频繁打网络。 */
    private const val DEBOUNCE_MS = 1500L

    /** 上传失败后的重试间隔，避免后端不可用时高频空转。 */
    private const val RETRY_MS = 3000L

    /** 记录各同步 key 本地最后修改时间（秒）的存储文件（按账号分区）。 */
    private const val META_PREFS = "sync_meta_prefs"

    /** 修改时间戳在 [META_PREFS] 中的 key 后缀，完整 key 形如 `affection__updated_at`。 */
    private const val TS_SUFFIX = "__updated_at"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val lock = Any()

    /** 待上传的 key（防抖窗口内累积） */
    private val pendingKeys = mutableSetOf<String>()

    /** 唯一的后台上传 worker；为 null 或已结束时，[notifyChanged] 才会新起一个。 */
    private var worker: Job? = null

    /** 本次进程内已成功同步过的 token，避免 Activity 重建时重复拉取 */
    @Volatile
    private var syncedToken: String? = null

    /**
     * 登录后的首次全量拉取是否已结束。
     *
     * 签到弹窗判断「今天是否已签到」读的是本地缓存，而刚切换到某个账号时该账号本地
     * 数据可能尚未拉取，此时会误判成「今天没签到」而每次登录都弹窗。
     * 弹窗前需等本标记为 true，确保已拿到服务端的签到数据。
     */
    @Volatile
    var loginSyncDone: Boolean = false
        private set

    // ==================== 登录后拉取 ====================

    /**
     * 登录成功后调用：拉取后端业务数据并写入本地缓存。
     *
     * 同一 token 在本进程内只拉取一次；拉取失败不置位，网络恢复后重新进入 App 会再次触发。
     * [onComplete] 在拉取结束后于主线程回调，供界面刷新展示最新数据。
     */
    fun syncOnLogin(context: Context, onComplete: (() -> Unit)? = null) {
        val appContext = context.applicationContext
        val token = SessionManager.token ?: return
        synchronized(lock) {
            if (token == syncedToken) return
            syncedToken = token
            // 新一轮拉取开始：先熄灭「首次拉取已完成」，避免切换账号后仍用上一个账号的
            // 完成状态，导致签到弹窗等逻辑读到尚未拉取的本地数据。
            loginSyncDone = false
        }
        scope.launch {
            val ok = runCatching { pullAll(appContext) }.isSuccess
            if (!ok) {
                synchronized(lock) { if (syncedToken == token) syncedToken = null }
            }
            loginSyncDone = true
            onComplete?.let { callback ->
                withContext(Dispatchers.Main) { callback() }
            }
        }
    }

    /**
     * 拉取后端全部业务文档并按 LWW 合并进本地缓存；本地较新的数据合并后回传补齐。
     *
     * 实现放在 [importDoc]：逐条比较本地与远端的修改时间，远端更新才采信远端，
     * 否则保留本地并标记需回传。
     */
    private suspend fun pullAll(context: Context) {
        val resp = AuthClient.api.pullSyncData(SessionManager.bearerToken())
        if (resp.code != 0) return
        val localOnlyKeys = mutableListOf<String>()
        resp.data?.docs?.forEach { doc ->
            val localOnly = runCatching {
                importDoc(context, doc.key, doc.value, doc.updatedAt ?: 0L)
            }.getOrDefault(false)
            if (localOnly) localOnlyKeys.add(doc.key)
        }
        // 本地存在比远端更新的数据（例如某天签到或一次好感度改动没上传成功）：
        // 把本地结果回传，避免服务端永久落后。回传失败不影响本次拉取，留待下次再补。
        if (localOnlyKeys.isNotEmpty()) runCatching { push(context, localOnlyKeys) }
    }

    // ==================== 账号切换 ====================

    /**
     * 进入主界面前调用：把同步相关的进程级内存态重置一遍，为当前账号重新拉取做准备。
     *
     * 本地数据已按账号分区（见 [EdenStorage]），切换账号只切换读写分区，不再清空任何数据，
     * 因此这里不需要也不应该动落盘数据——上一个账号的数据留在它自己的分区里，
     * 下次登回来仍在。但 [syncedToken] / [loginSyncDone] 以及进行中的上传 worker 是
     * 进程级共享的内存态，必须重置，否则新账号会被判定成「已同步过」而跳过拉取。
     *
     * @param userId 当前登录账号 id；为 0（服务端未返回 id）时直接返回。
     */
    suspend fun prepareForUser(context: Context, userId: Long) {
        if (userId == 0L) return
        resetInMemoryState()
        ChatSession.reset()
    }

    /**
     * 退出登录：清除登录态并重置进程级内存态。
     *
     * 注意这里**不清空本地数据**：各账号的数据保存在各自的分区里，退出登录只是切换到
     * 无账号分区，登回来时数据仍在（[EdenStorage] 提供了兼容旧版的首次迁移）。
     * [onComplete] 在完成后于主线程回调，供界面跳转到登录页。
     */
    fun logoutAndResetSession(context: Context, onComplete: () -> Unit) {
        val appContext = context.applicationContext
        // 同步清零内存态必须在 SessionManager.logout 之前：logout 会把 currentUserId 置 0，
        // 之后的数据库/文件操作就会指向公共区。
        resetInMemoryState()
        SessionManager.logout(appContext)
        ChatSession.reset()
        onComplete()
    }

    /** 重置进程级的同步内存态：取消进行中的上传、清空待上传队列与已同步标记。 */
    private fun resetInMemoryState() {
        synchronized(lock) {
            worker?.cancel()
            worker = null
            pendingKeys.clear()
            syncedToken = null
            loginSyncDone = false
        }
    }

    // ==================== 本地变更后上传 ====================

    /** 本地某类业务数据变更后调用：记录修改时间，防抖合并后批量上传到后端。未登录时只记录时间。 */
    fun notifyChanged(context: Context, key: String) {
        val appContext = context.applicationContext
        // 无论是否登录都记录本地修改时刻：LWW 拉取时用它判断本地与远端谁更新。
        setLocalUpdatedAt(appContext, key, nowSeconds())
        if (!SessionManager.isLoggedIn(context)) return
        synchronized(lock) {
            pendingKeys.add(key)
            // 已有 worker 在跑时只登记 key，由它本轮/下一轮统一带走；
            // 没有 worker 才新起一个，避免并发上传互相覆盖。
            if (worker?.isActive == true) return
            worker = scope.launch { drain(appContext) }
        }
    }

    /**
     * 单一 worker 循环：等一个防抖窗口后取出全部待上传 key 批量上报。
     * 上传失败时把 key 放回集合稍后重试，保证变更不会因为一次网络抖动而丢失。
     */
    private suspend fun drain(context: Context) {
        while (true) {
            delay(DEBOUNCE_MS)
            val keys: List<String>
            synchronized(lock) {
                if (pendingKeys.isEmpty()) {
                    worker = null
                    return
                }
                keys = pendingKeys.toList()
                pendingKeys.clear()
            }
            val ok = runCatching { push(context, keys) }.isSuccess
            if (!ok) {
                synchronized(lock) { pendingKeys.addAll(keys) }
                delay(RETRY_MS)
            }
        }
    }

    /** 把若干 key 对应的本地数据导出后，连同本地修改时间一次性上传到后端。 */
    private suspend fun push(context: Context, keys: List<String>) {
        val docs = keys.mapNotNull { key ->
            val value = runCatching { exportDoc(context, key) }.getOrNull() ?: return@mapNotNull null
            // 带上本地修改时间，后端据此做「仅更新较新」的写入，避免旧值覆盖新值。
            SyncDoc(
                key = key,
                value = value,
                updatedAt = localUpdatedAt(context, key).takeIf { it > 0L },
            )
        }
        if (docs.isEmpty()) return
        AuthClient.api.pushSyncData(SessionManager.bearerToken(), SyncPushRequest(docs))
    }

    // ==================== 本地修改时间戳 ====================

    /** 某个同步 key 的本地最后修改时间（秒）；从未记录过返回 0。 */
    private fun localUpdatedAt(context: Context, key: String): Long =
        metaPrefs(context).getLong(tsKey(key), 0L)

    /** 记录某个同步 key 的本地最后修改时间（秒），仅接受正数。 */
    private fun setLocalUpdatedAt(context: Context, key: String, ts: Long) {
        if (ts > 0L) metaPrefs(context).edit().putLong(tsKey(key), ts).apply()
    }

    private fun tsKey(key: String) = "$key$TS_SUFFIX"

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    private fun metaPrefs(context: Context) =
        EdenStorage.prefs(context, META_PREFS)

    // ==================== key ↔ 本地存储映射 ====================

    /** 导出某个 key 对应的本地数据为 JSON 字符串。 */
    private suspend fun exportDoc(context: Context, key: String): String = when {
        key.startsWith(SyncKeys.CHAT_PREFIX) ->
            ChatRepository(context).exportJson(key.removePrefix(SyncKeys.CHAT_PREFIX))
        key == SyncKeys.DIARY -> DiaryRepository(context).exportJson()
        key == SyncKeys.SIGN_IN -> SignInRepository.exportJson(context)
        key == SyncKeys.AFFECTION -> EconomyManager.exportAffectionJson(context)
        key == SyncKeys.ANNIVERSARY -> DailyLoopManager.exportAnniversaryJson(context)
        key == SyncKeys.SETTINGS -> ProfileRepository.exportJson(context)
        key == SyncKeys.WALLET -> EconomyManager.exportWalletJson(context)
        else -> throw IllegalArgumentException("未知同步 key: $key")
    }

    /**
     * 按 LWW 用后端文档合并某个 key 对应的本地数据。
     *
     * @param remoteUpdatedAt 后端该文档的修改时间（秒），未知传 0。
     * @return true 表示本地数据比远端更新，调用方应把本地结果回传后端。
     *
     * 规则：
     * - 远端时间更晚 → 采信远端，覆盖本地并记住该时间；
     * - 本地同样新或更新 → 保留本地，本地更晚时回传补齐远端。
     * 签到是「只增不减」的集合，单独走并集合并（见 [SignInRepository.importJson]）。
     */
    private suspend fun importDoc(
        context: Context,
        key: String,
        json: String,
        remoteUpdatedAt: Long,
    ): Boolean {
        val localTs = localUpdatedAt(context, key)

        if (key == SyncKeys.SIGN_IN) {
            val localOnly = SignInRepository.importJson(context, json)
            setLocalUpdatedAt(context, key, maxOf(localTs, remoteUpdatedAt))
            return localOnly
        }

        if (remoteUpdatedAt > localTs) {
            applyRemoteDoc(context, key, json)
            setLocalUpdatedAt(context, key, remoteUpdatedAt)
            return false
        }
        return localTs > remoteUpdatedAt
    }

    /** 用后端 JSON 覆盖某个 key 对应的本地数据（不走签到；不做上传回调，避免回环）。 */
    private suspend fun applyRemoteDoc(context: Context, key: String, json: String) {
        when {
            key.startsWith(SyncKeys.CHAT_PREFIX) ->
                ChatRepository(context).importJson(key.removePrefix(SyncKeys.CHAT_PREFIX), json)
            key == SyncKeys.DIARY -> DiaryRepository(context).importJson(json)
            key == SyncKeys.AFFECTION -> EconomyManager.importAffectionJson(context, json)
            key == SyncKeys.ANNIVERSARY -> DailyLoopManager.importAnniversaryJson(context, json)
            key == SyncKeys.SETTINGS -> ProfileRepository.importJson(context, json)
            key == SyncKeys.WALLET -> EconomyManager.importWalletJson(context, json)
        }
    }
}
