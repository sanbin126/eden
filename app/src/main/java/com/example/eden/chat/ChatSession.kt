package com.example.eden.chat

import android.app.Application
import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.eden.AffectionManager
import com.example.eden.AuthManager
import com.example.eden.ChatMessage
import com.example.eden.DailyLoopManager
import com.example.eden.LoopStep
import com.example.eden.MessageType
import com.example.eden.MilestoneManager
import com.example.eden.NicknameManager
import com.example.eden.R
import com.example.eden.SyncManager
import com.example.eden.WalletManager
import com.example.eden.character.MikuPersona
import com.example.eden.data.ChatMemory
import com.example.eden.data.ChatRepository
import com.example.eden.data.DiaryEntry
import com.example.eden.data.DiaryRepository
import com.example.eden.ui.theme.LlmException
import com.example.eden.ui.theme.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * 进程级的聊天会话：持有消息列表与 AI 生成逻辑本身。
 *
 * 之所以不放在 [ChatViewModel] 里，是因为回复生成绑定在 `viewModelScope` 上，
 * Activity 一退出（左滑/返回）就会被取消，AI 还没答完就被打断。
 * 这里用一个不随页面销毁的 [scope]，退出聊天页后仍会继续思考并写完整句回复，
 * 完成后照常落库；再次进入聊天页时从数据库读到的是完整答案。
 */
object ChatSession {

    /** 与页面生命周期解耦的作用域：只有在进程结束（或应用被杀）时才会停 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ==================== 聊天的产出与好感（数值集中在此，便于调整） ====================
    /** 每条用户消息产出的「円」 */
    private const val YEN_PER_MESSAGE = 2
    /** 聊天产币的每日上限 */
    private const val YEN_DAILY_CAP = 30
    /** 每 N 条消息加 1 点好感（聊天好感被刻意降权，引导去投喂/送礼） */
    private const val MESSAGES_PER_AFFECTION = 3
    /** 聊天好感的每日上限 */
    private const val AFFECTION_DAILY_CAP = 5
    /** WalletManager 计数器 key（内部按自然日重置） */
    private const val COUNTER_MESSAGE = "chat_msg"
    private const val COUNTER_YEN = "chat_yen"
    private const val COUNTER_AFFECTION = "chat_aff"

    /** 「聊天产币触顶」提示的去重计数器：每天只念一次出口台词 */
    private const val COUNTER_CAP_HINT = "chat_cap_hint"

    private var initialized = false
    private lateinit var repository: ChatRepository
    private lateinit var diaryRepository: DiaryRepository
    private lateinit var appContext: Context

    private val _messages = MutableLiveData<List<ChatMessage>>(emptyList())
    val messages: LiveData<List<ChatMessage>> = _messages

    /** 需要提示给用户的文案（网络失败原因等） */
    private val _toast = MutableLiveData<Event<String>>()
    val toast: LiveData<Event<String>> = _toast

    /** 是否正在流式生成 AI 回复，期间禁用发送，避免并发请求把消息列表搞乱 */
    private val _isReplying = MutableLiveData(false)
    val isReplying: LiveData<Boolean> = _isReplying

    /** 只加载一次欢迎语与历史记录，页面重建时不重复插入 */
    private var loaded = false

    /**
     * 长期记忆（滚动摘要 + 结构化事实）。
     * 每次请求时随人设一起注入，弥补滑动窗口之外的信息丢失。
     */
    private var memory: ChatMemory = ChatMemory.EMPTY

    /**
     * 最近的日记摘要（今天 / 昨天），仅在用户主动发消息时刷新。
     * 注入到人设之后，让三玖能在对话里自然地提起用户写过的日记；
     * 属于时效性素材，随时间推进自动失效，不会沉淀进长期记忆。
     */
    private var diaryContext: String? = null

    fun init(application: Application) {
        if (initialized) return
        repository = ChatRepository(application)
        diaryRepository = DiaryRepository(application)
        appContext = application.applicationContext
        initialized = true
    }

    // ==================== 初始化 ====================

    fun loadMessages() {
        if (loaded) return
        loaded = true
        scope.launch {
            // 登录态下先等首次全量同步结束再读库。否则刚登录时本地数据还没拉回来，
            // 会读到空列表并插入欢迎语，把界面「冻结」在仅欢迎语的状态；
            // 之后同步写入的历史记录再也不会显示（且本地会被误判为更新而回传覆盖服务端）。
            awaitInitialSync()

            // 日期分隔线改为渲染时动态计算，这里过滤掉并清理旧版本落库遗留的分隔线
            val list = repository.load(MikuPersona.ID)
                .filter { it.type != MessageType.TYPE_DIVIDER }
                .toMutableList()
            repository.deleteDividers(MikuPersona.ID)

            if (list.isEmpty()) {
                val welcome = ChatMessage(
                    content = MikuPersona.WELCOME_MESSAGES.random(),
                    type = MessageType.TYPE_RECEIVED
                )
                list.add(welcome)
                repository.append(MikuPersona.ID, welcome)
            }

            _messages.value = list
            memory = repository.loadMemory(MikuPersona.ID)
            // 补齐可能存在的历史积压：老用户升级后既有记录会在这里被首次压缩进记忆
            refreshMemory()
        }
    }

    /** 等首次登录全量同步结束；未登录或已完成时立即返回，避免读库早于数据拉取。 */
    private suspend fun awaitInitialSync() {
        if (!AuthManager.isLoggedIn(appContext) || SyncManager.loginSyncDone) return
        var waited = 0L
        while (!SyncManager.loginSyncDone && waited < SYNC_WAIT_TIMEOUT_MS) {
            delay(SYNC_WAIT_STEP_MS)
            waited += SYNC_WAIT_STEP_MS
        }
    }

    /**
     * 重置会话内存态：切换账号 / 退出登录清空本地数据后调用。
     *
     * [ChatSession] 是进程级单例，其缓存的 `loaded` / 消息列表 / 长期记忆会跨账号残留。
     * 若不重置，新账号登录后聊天页仍停留在上一账号（或本地空库时的欢迎语）的内容，
     * 看不到刚从后端同步回来的历史记录——表现为「好感度等恢复了，聊天记录却没有」。
     */
    fun reset() {
        scope.launch {
            loaded = false
            memory = ChatMemory.EMPTY
            diaryContext = null
            _isReplying.value = false
            _messages.value = emptyList()
        }
    }

    // ==================== 发送 ====================

    /**
     * 发送一条用户消息。
     * 命中固定台词池则本地回复（延时 2 秒模拟打字），否则流式请求大模型。
     * 正在流式回复时直接忽略，避免并发请求。
     */
    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _isReplying.value == true) return

        val fixed = MikuPersona.fixedReplyFor(trimmed)
        val user = ChatMessage(content = trimmed, type = MessageType.TYPE_SENT)
        append(user)

        scope.launch {
            // 落库后回填主键，删除/重新生成等长按操作需要 id
            val id = repository.append(MikuPersona.ID, user)
            replaceMessage(user, user.copy(id = id))
            // 每日循环：发过消息即算完成「聊天」这一步
            DailyLoopManager.markDone(appContext, LoopStep.CHAT)
            // 聊天产出「円」（每日封顶，避免刷屏）
            WalletManager.earn(appContext, COUNTER_YEN, YEN_PER_MESSAGE, YEN_DAILY_CAP)
            // 聊天加好感刻意降权：每 MESSAGES_PER_AFFECTION 条才 +1，且每日封顶；跨过 10 的倍数时登记里程碑
            val messageCount = WalletManager.addDaily(appContext, COUNTER_MESSAGE, 1)
            if (messageCount % MESSAGES_PER_AFFECTION == 0 &&
                WalletManager.tryDailyGrant(appContext, COUNTER_AFFECTION, AFFECTION_DAILY_CAP) == 1
            ) {
                val (newAffection, crossedMilestone) = AffectionManager.increase(appContext, 1)
                if (crossedMilestone) MilestoneManager.onCrossed(appContext, newAffection)
            }

            if (fixed != null) {
                delay(FIXED_REPLY_DELAY_MS)
                appendReceived(fixed)
            } else {
                streamReply()
            }

            // 聊天产币触顶时，角色主动说一句把人引向「专注」（每天仅一次，避免反复刷屏）
            if (WalletManager.dailyValue(appContext, COUNTER_YEN) >= YEN_DAILY_CAP &&
                WalletManager.tryDailyGrant(appContext, COUNTER_CAP_HINT, 1) == 1
            ) {
                appendReceived(MikuPersona.CHAT_CAP_HINT_LINES.random())
            }
        }
    }

    /** 收到 401 等场景下由外部主动插入一条角色消息（如主动问候） */
    fun appendReceived(content: String) {
        val message = ChatMessage(content = content, type = MessageType.TYPE_RECEIVED)
        append(message)
        scope.launch {
            // 先落库再压缩：摘要基于数据库全量历史，必须保证刚生成的回复已写入
            val id = repository.append(MikuPersona.ID, message)
            replaceMessage(message, message.copy(id = id))
            refreshMemory()
        }
    }

    /** 点击「回复失败」气泡时重试：移除错误气泡后重新请求 */
    fun retry() {
        val current = _messages.value.orEmpty()
        if (current.lastOrNull()?.type != MessageType.TYPE_ERROR) return
        _messages.value = current.dropLast(1)
        scope.launch { streamReply() }
    }

    /** 长按菜单：删除单条消息（列表与数据库同步删除） */
    fun deleteMessage(message: ChatMessage) {
        _messages.value = _messages.value.orEmpty().filterNot { it === message }
        scope.launch {
            if (message.id != 0L) repository.deleteMessage(message.id)
        }
    }

    /**
     * 长按菜单：重新生成某条角色回复。
     * 删掉该回复（列表 + 库），基于其之前的上下文重新流式请求，新回复追加到末尾。
     */
    fun regenerateReply(message: ChatMessage) {
        if (_isReplying.value == true) return
        val current = _messages.value.orEmpty()
        val idx = current.indexOfFirst { it === message }
        if (idx < 0 || message.type != MessageType.TYPE_RECEIVED) return

        val source = current.subList(0, idx).toList()
        _messages.value = current.filterNot { it === message }
        scope.launch {
            if (message.id != 0L) repository.deleteMessage(message.id)
            refreshDiaryContext()
            streamReplyWith(buildPayload(source))
        }
    }

    /** 清空聊天记录与长期记忆，并重新插入欢迎语 */
    fun clearHistory() {
        scope.launch {
            repository.clear(MikuPersona.ID)
            memory = ChatMemory.EMPTY
            val welcome = ChatMessage(
                content = MikuPersona.WELCOME_MESSAGES.random(),
                type = MessageType.TYPE_RECEIVED
            )
            repository.append(MikuPersona.ID, welcome)
            _messages.value = listOf(welcome)
        }
    }

    // ==================== 内部实现 ====================

    /**
     * 检查并把攒够的旧对话压缩进长期记忆。
     * 记忆属于增强能力，压缩失败不得影响正常聊天，因此异常一律吞掉（但不吞取消异常）。
     */
    private suspend fun refreshMemory() {
        val updated = try {
            repository.summarizeIfNeeded(MikuPersona.ID)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (updated != null) memory = updated
    }

    /**
     * 请求大模型回复。
     * 先刷新日记上下文、构造消息体（此时列表最后是刚发送的用户消息，不带任何占位气泡，
     * 避免空 assistant 消息污染上下文），再交给 [streamReplyWith] 请求。
     */
    private suspend fun streamReply() {
        refreshDiaryContext()
        streamReplyWith(buildPayload())
    }

    /**
     * 基于 [payload] 生成回复：请求期间只在内存中累积内容，不向列表插入占位气泡，
     * 等整段回复生成完毕后再一次性落库并追加显示——思考阶段界面不预留任何空白。
     * 失败时提示重试。期间置 [isReplying]，禁止并发发送。
     */
    private suspend fun streamReplyWith(payload: List<Message>) {
        _isReplying.value = true
        val accum = StringBuilder()
        try {
            repository.requestReplyStream(payload).collect { delta ->
                accum.append(delta)
            }
            if (accum.isBlank()) throw IOException("AI 回复为空")
            val reply = ChatMessage(content = accum.toString(), type = MessageType.TYPE_RECEIVED)
            val rowId = repository.append(MikuPersona.ID, reply)
            append(reply.copy(id = rowId))
            refreshMemory()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _toast.value = Event(describeError(e))
            append(ChatMessage(content = appContext.getString(R.string.item_message_error), type = MessageType.TYPE_ERROR))
        } finally {
            _isReplying.value = false
        }
    }

    /**
     * 构造发给后端的消息体，顺序为：
     * `system 人设 → system 长期记忆（可选）→ system 日记（可选）→ 最近 MAX_HISTORY 条原文`。
     *
     * 记忆放在人设之后、原文之前，让模型先知道「用户是谁、聊过什么」，
     * 再用最近几轮原文保持语气与上下文连贯。
     *
     * [source] 默认为当前消息列表；「重新生成」时传入目标消息之前的子列表，
     * 避免把要删掉的旧回复及其后续消息作为上下文。
     */
    private fun buildPayload(source: List<ChatMessage> = _messages.value.orEmpty()): List<Message> {
        val call = NicknameManager.getCallRaw(appContext)
        val formal = NicknameManager.getFormal(appContext)
        val intimate = NicknameManager.getIntimateRaw(appContext)
        val payload = mutableListOf(Message(role = "system", content = MikuPersona.systemPrompt(call, formal, intimate)))
        memoryContext()?.let { payload.add(Message(role = "system", content = it)) }
        diaryContext?.let { payload.add(Message(role = "system", content = it)) }
        milestoneContext()?.let { payload.add(Message(role = "system", content = it)) }
        source
            .filter { it.type == MessageType.TYPE_SENT || it.type == MessageType.TYPE_RECEIVED }
            .takeLast(MAX_HISTORY)
            .forEach { msg ->
                val role = if (msg.type == MessageType.TYPE_SENT) "user" else "assistant"
                payload.add(Message(role = role, content = msg.content))
            }
        return payload
    }

    /** 把长期记忆拼成一条 system 消息；没有记忆时返回 null，避免注入空内容浪费 token */
    private fun memoryContext(): String? {
        if (memory.isEmpty) return null
        return buildString {
            appendLine("以下是你此前陪伴这位用户时积累的长期记忆，请在回复时自然地运用（称呼、偏好、约定等），但不要直接复述这段内容：")
            if (memory.facts.isNotEmpty()) {
                appendLine("【关于用户的事实】")
                memory.facts.forEach { appendLine("- $it") }
            }
            if (memory.summary.isNotBlank()) {
                appendLine("【过往聊天摘要】")
                appendLine(memory.summary)
            }
        }.trim()
    }

    /**
     * 好感度刚刚突破里程碑时，注入一条一次性的关系升温提示，让角色"感知"到关系更近了一步。
     * 用完即清（[MilestoneManager.consumeAiHint]），因此每段关系只会自然升温一次，不会反复强调。
     */
    private fun milestoneContext(): String? {
        val milestone = MilestoneManager.consumeAiHint(appContext) ?: return null
        return "【系统提示】你与用户的好感度刚刚突破到 $milestone，此刻你比平时更愿意向对方袒露一点真心，" +
            "但仍然保持害羞、含蓄的语气。不要直接提及好感度或数值。"
    }

    /**
     * 读取最近（今天 / 昨天）写过的日记摘要，拼成一段 system 提示备用。
     * 只在用户主动发起对话时刷新，让三玖能在聊天中自然提起，而不是无条件主动打扰。
     * 读取失败静默（日记只是锦上添花，不能影响正常聊天，但取消异常必须透传）。
     */
    private suspend fun refreshDiaryContext() {
        diaryContext = try {
            diaryRepository.recentEntries().takeIf { it.isNotEmpty() }?.let { formatDiaryContext(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** 把日记摘要拼成一条 system 消息，引导角色找合适时机自然地关心用户 */
    private fun formatDiaryContext(entries: List<DiaryEntry>): String = buildString {
        appendLine("以下是用户最近写下的心情日记，请在这次对话中找合适时机自然地关心一下（像真正在意对方一样，不要生硬复述原文，也不必每句都提）：")
        entries.forEach { entry ->
            val text = entry.summary.ifBlank { entry.content.replace('\n', ' ').trim().take(40) }
            appendLine("- ${entry.date}（心情${entry.mood}）：$text")
        }
    }.trim()

    private fun append(message: ChatMessage) {
        _messages.value = _messages.value.orEmpty() + message
    }

    /** 按对象身份定位并替换（落库后回填主键，旧对象引用仍在调用方手上） */
    private fun replaceMessage(old: ChatMessage, new: ChatMessage) {
        val current = _messages.value.orEmpty()
        val idx = current.indexOfFirst { it === old }
        if (idx < 0) return
        _messages.value = current.toMutableList().apply { set(idx, new) }
    }

    private fun describeError(e: Throwable): String = when {
        // 后端 / 直连模式的业务错误：直接展示原始文案（已含 HTTP 状态或具体原因），再补重试入口
        e is LlmException -> "${e.message ?: "AI 回复失败"}，请点击重试"
        e is HttpException -> "AI 服务异常（HTTP ${e.code()}），请点击重试"
        e is SocketTimeoutException -> "AI 响应超时，请点击重试"
        e is IOException -> "网络连接失败，请检查网络后点击重试"
        else -> "AI 回复失败：${e.message ?: "未知错误"}"
    }

    private const val FIXED_REPLY_DELAY_MS = 2000L

    /** 读库前等待首次全量同步的轮询步长 */
    private const val SYNC_WAIT_STEP_MS = 50L

    /** 读库前等待首次全量同步的最长时间，超时则照常读取本地（不无限阻塞聊天页） */
    private const val SYNC_WAIT_TIMEOUT_MS = 10_000L

    /**
     * 保留的原文条数（12 轮）。
     * 超出这个窗口的信息由长期记忆承担，因此窗口不需要开太大，省下的额度留给记忆。
     */
    private const val MAX_HISTORY = 24
}
