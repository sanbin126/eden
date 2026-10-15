package com.example.eden

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.eden.character.MikuPersona
import com.example.eden.chat.ChatViewModel
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.launch

/**
 * 聊天页。
 *
 * 这里只保留「界面渲染 + 系统能力（通知/定位/相册）」，
 * 消息数据、Room 持久化、AI 请求与失败重试都交给 [ChatViewModel]。
 */
class ChatActivity : AppCompatActivity() {

    private companion object {
        const val CHANNEL_ID = "chat_greeting"
        const val NOTIFICATION_ID = 1001
        const val REQUEST_LOCATION = 101
        const val REQUEST_NOTIFICATION = 102

        /** 陪伴时长落盘周期：30 秒一次，避免长时间停留在聊天页时主界面数据不更新 */
        const val COMPANY_FLUSH_INTERVAL_MS = 30_000L
    }

    private val viewModel: ChatViewModel by viewModels()

    private lateinit var rvChat: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var tvCharacterName: TextView
    private lateinit var tvCharacterStatus: TextView
    private lateinit var btnMenu: ImageView
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var btnSend: Button
    private lateinit var btnBack: Button
    private lateinit var btnGoFocus: TextView
    private lateinit var btnGoFeed: TextView

    private var timerHandler: Handler? = null
    private var lastFlushTimestamp: Long = 0L

    /** 首次列表就位时直接跳到末尾（不带滚动动画），之后的新消息才走平滑滚动 */
    private var jumpedToBottom = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        createNotificationChannel()
        requestNotificationPermissionIfNeeded()
        initViews()
        setupRecyclerView()
        setupClickListeners()
        observeViewModel()

        viewModel.loadMessages()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        startCompanyTimer()
        // 主动关心逻辑
        lifecycleScope.launch { checkAndSendGreeting() }
    }

    override fun onPause() {
        super.onPause()
        flushCompanyTime()
        timerHandler?.removeCallbacksAndMessages(null)
        timerHandler = null
        GreetingManager.recordExitTime(this)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 权限已授予，再次尝试获取天气
                lifecycleScope.launch { checkAndSendGreeting() }
            } else {
                Toast.makeText(this, "需要定位权限才能获取天气", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ==================== 陪伴时长 ====================

    private fun startCompanyTimer() {
        lastFlushTimestamp = System.currentTimeMillis()
        timerHandler = Handler(Looper.getMainLooper())
        timerHandler?.post(object : Runnable {
            override fun run() {
                flushCompanyTime()
                timerHandler?.postDelayed(this, COMPANY_FLUSH_INTERVAL_MS)
            }
        })
    }

    /** 把「距上次落盘经过的秒数」累加进今日陪伴时长 */
    private fun flushCompanyTime() {
        val now = System.currentTimeMillis()
        val elapsedSeconds = ((now - lastFlushTimestamp) / 1000).toInt()
        lastFlushTimestamp = now
        CompanyTimeManager.addSeconds(this, elapsedSeconds)
    }

    // ==================== 视图初始化 ====================

    private fun initViews() {
        rvChat = findViewById(R.id.rv_chat)
        etInput = findViewById(R.id.et_input)
        btnSend = findViewById(R.id.btn_send)
        btnBack = findViewById(R.id.btn_back)
        tvCharacterName = findViewById(R.id.tv_character_name)
        tvCharacterStatus = findViewById(R.id.tv_character_status)
        btnMenu = findViewById(R.id.btn_menu)
        btnGoFocus = findViewById(R.id.btn_go_focus)
        btnGoFeed = findViewById(R.id.btn_go_feed)
        tvCharacterName.text = MikuPersona.NAME
        tvCharacterStatus.text = affectionStatus()
        etInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                btnSend.isEnabled = !s.isNullOrEmpty() && viewModel.isReplying.value != true
            }
        })
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter(
            onErrorClick = { viewModel.retry() },
            onMessageLongClick = { msg, _ -> showMessageOptions(msg) }
        )
        rvChat.layoutManager = LinearLayoutManager(this)
        rvChat.adapter = chatAdapter
        rvChat.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom < oldBottom) {
                rvChat.postDelayed({
                    rvChat.smoothScrollToPosition(chatAdapter.itemCount - 1)
                }, 100)
            }
        }
    }

    /**
     * 长按消息气泡弹出操作菜单：复制 / 删除 / 重新生成。
     * 「重新生成」仅对角色消息（TYPE_RECEIVED）显示；自己发的消息隐藏该选项。
     */
    private fun showMessageOptions(message: ChatMessage) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_message_options, null)

        view.findViewById<LinearLayout>(R.id.item_copy).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("eden_message", message.content))
            showToast("已复制")
            dialog.dismiss()
        }

        val itemRegenerate = view.findViewById<LinearLayout>(R.id.item_regenerate)
        if (message.type == MessageType.TYPE_RECEIVED) {
            itemRegenerate.setOnClickListener {
                viewModel.regenerateReply(message)
                dialog.dismiss()
            }
        } else {
            itemRegenerate.visibility = View.GONE
        }

        view.findViewById<LinearLayout>(R.id.item_delete).setOnClickListener {
            viewModel.deleteMessage(message)
            dialog.dismiss()
        }

        dialog.setContentView(view)
        // 去掉 BottomSheet 默认白底，让自定义粉色圆角面板生效
        (view.parent as? View)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        dialog.show()
    }

    private fun setupClickListeners() {
        btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        btnSend.setOnClickListener { sendMessage() }
        etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendMessage(); true
            } else false
        }
        btnMenu.setOnClickListener { showSettingsDialog() }
        // 循环轻入口：聊天 → 专注 / 投喂
        btnGoFocus.setOnClickListener {
            startActivity(Intent(this, FocusTimerActivity::class.java))
        }
        btnGoFeed.setOnClickListener { showGiftPicker() }
    }

    private fun observeViewModel() {
        viewModel.messages.observe(this) { messages ->
            chatAdapter.submit(messages)
            if (messages.isEmpty()) return@observe
            if (jumpedToBottom) {
                rvChat.smoothScrollToPosition(chatAdapter.itemCount - 1)
            } else {
                // 首次进入：历史记录一次性铺开后直接定位到末尾，
                // 避免从第一条开始「滚下来」的抖动
                jumpedToBottom = true
                rvChat.scrollToPosition(chatAdapter.itemCount - 1)
            }
        }
        viewModel.toast.observe(this) { event ->
            event.getIfNotHandled()?.let { showToast(it) }
        }
        viewModel.isReplying.observe(this) { busy ->
            // 生成期间禁用发送，避免并发请求；同时刷新状态栏的「正在输入」提示
            btnSend.isEnabled = !busy && !etInput.text.isNullOrEmpty()
            updateStatusText()
        }
    }

    // ==================== 状态栏 ====================

    /**
     * 刷新标题下方的状态文案。
     * AI 正在生成回复时显示「对方正在输入...」，其余时刻按好感度显示在线状态。
     */
    private fun updateStatusText() {
        tvCharacterStatus.text =
            if (viewModel.isReplying.value == true) "对方正在输入..." else affectionStatus()
    }

    /** 根据好感度返回日常状态文案 */
    private fun affectionStatus(): String = when {
        AffectionManager.getAffection(this) >= 80 -> "爱你"
        AffectionManager.getAffection(this) >= 40 -> "想你"
        else -> getString(R.string.chat_online)
    }

    // ==================== 消息发送 ====================
    private fun sendMessage() {
        val messageText = etInput.text.toString().trim()
        if (messageText.isEmpty()) return
        etInput.text.clear()
        viewModel.send(messageText)
    }

    // ==================== 主动关心 ====================

    private suspend fun checkAndSendGreeting() {
        val context = this@ChatActivity

        // 「主动问候」开关关闭时，直接跳过（同时避免无谓的定位授权请求）
        if (!SettingsManager.isGreetingEnabled(context)) return

        // 先请求定位权限（Android 10+ 建议在运行时请求）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_LOCATION)
                return  // 等授权回调后再试
            }
        }

        val shouldSendMorning = GreetingManager.shouldSendMorningGreeting(context)
        val shouldSendReturn = GreetingManager.shouldSendReturnGreeting(context)

        if (shouldSendMorning || shouldSendReturn) {
            val weather = WeatherManager.getWeather(context)
            // 与后台推送复用同一套文案，避免前后台措辞不一致
            val nickname = NicknameManager.getCallRaw(context)
            val message = when {
                shouldSendMorning -> GreetingNotifier.buildMorningMessage(weather, nickname)
                shouldSendReturn -> GreetingNotifier.buildReturnMessage(weather, nickname)
                else -> null
            }
            if (message != null) {
                viewModel.appendReceived(message)
                sendGreetingNotification(message)
                if (shouldSendMorning) GreetingManager.markMorningSent(context)
                if (shouldSendReturn) GreetingManager.markReturnSent(context)
            }
        }
    }

    // ==================== 通知相关 ====================
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "三玖的问候"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    // Android 13(API 33) 起发送通知需要运行时授予 POST_NOTIFICATIONS，否则 notify() 会静默失败
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION)
        }
    }

    private fun sendGreetingNotification(message: String) {
        // 「消息通知」开关关闭时不弹系统通知，但消息本身仍会出现在聊天列表里
        if (!SettingsManager.isNotificationEnabled(this)) return

        val intent = Intent(this, ChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle(MikuPersona.NAME)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    // ==================== 设置弹窗 ====================
    private fun showSettingsDialog() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_chat_settings, null)
        view.findViewById<LinearLayout>(R.id.item_clear_chat).setOnClickListener {
            ThemedDialog.show(
                context = this,
                title = getString(R.string.chat_settings_clear),
                message = "确定要清空所有聊天记录吗？记忆也会被清除。",
                positiveText = getString(R.string.common_confirm),
                iconRes = R.drawable.ic_delete,
                iconTint = edenColor(R.attr.edenColorPrimaryPressed, R.color.eden_primary_pressed)
            ) { viewModel.clearHistory() }
            dialog.dismiss()
        }
        dialog.setContentView(view)
        // 去掉 BottomSheet 默认白底，让自定义粉色圆角面板生效
        (view.parent as? View)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        dialog.show()
    }

    // ==================== 投喂 ====================

    /**
     * 投喂面板：底部弹出，按 饮料 / 食物 / 礼物 分组列出物品。
     *
     * 与主页共用 [GiftManager] 结算；投喂成功后三玖的回应直接以聊天消息的形式出现，
     * 不用气泡 —— 聊天页里消息本身就是最自然的反馈。
     */
    private fun showGiftPicker() {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_gift_picker, null)
        sheet.setContentView(view)
        (view.parent as? View)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        val container = view.findViewById<LinearLayout>(R.id.giftContainer)
        val tvBalance = view.findViewById<TextView>(R.id.tvGiftBalance)

        fun refreshBalance() {
            tvBalance.text = "持有：${WalletManager.getBalance(this)} 円"
        }

        val headerPadding = (10 * resources.displayMetrics.density).toInt()
        var lastKind: GiftKind? = null
        GiftCatalog.ALL.forEach { item ->
            // 分组小标题（饮料 / 食物 / 礼物）
            if (item.kind != lastKind) {
                lastKind = item.kind
                val header = TextView(this).apply {
                    text = item.kind.label
                    setTextColor(edenColor(R.attr.edenColorAccent, R.color.eden_accent))
                    textSize = 13f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, headerPadding, 0, 0)
                }
                container.addView(header)
            }

            val row = layoutInflater.inflate(R.layout.item_gift, container, false)
            row.findViewById<TextView>(R.id.tvGiftEmoji).text = item.emoji
            row.findViewById<TextView>(R.id.tvGiftName).text = item.name
            row.findViewById<TextView>(R.id.tvGiftCost).text = "${item.cost} 円"
            val subView = row.findViewById<TextView>(R.id.tvGiftSub)

            fun refreshRow() {
                if (GiftManager.isLimitReached(this, item)) {
                    subView.text = "已达限次（${item.limitLabel}）"
                    row.alpha = 0.5f
                } else {
                    subView.text = "好感 +${item.affection} · ${GiftManager.remainingLabel(this, item)}"
                    row.alpha = 1f
                }
            }
            refreshRow()

            row.setOnClickListener {
                when (val result = GiftManager.feed(this, item)) {
                    is GiftManager.FeedResult.Success -> {
                        viewModel.appendReceived(result.line)
                        // 投喂完成 = 每日循环「投喂」节点达成
                        DailyLoopManager.markDone(this, LoopStep.FEED)
                        sheet.dismiss()
                    }
                    is GiftManager.FeedResult.LimitReached ->
                        showToast("${item.name}${item.limitLabel}，先歇一歇吧")
                    is GiftManager.FeedResult.InsufficientYen ->
                        showToast("「円」不够啦，多和三玖聊聊天攒一攒")
                }
            }
            container.addView(row)
        }

        refreshBalance()
        sheet.show()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
