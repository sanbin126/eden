package com.example.eden

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.eden.character.MikuPersona
import com.example.eden.data.ChatRepository
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import android.graphics.PorterDuff
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class MainActivity : AppCompatActivity(), MusicPickerDialog.Listener {

    // ==================== 区域比例常量（方便反复调整） ====================
    companion object {
        // ===== 头部：红头发+脸部区域 0.08~0.42纵向，左右0.22~0.78 =====
        const val HEAD_TOP = 0.08f
        const val HEAD_BOTTOM = 0.42f
        const val HEAD_LEFT = 0.22f
        const val HEAD_RIGHT = 0.78f

        // ===== 耳机：挂在脖颈脸颊两侧，纵向下移 0.33~0.50，左右修正(不在最边缘) =====
        const val EARPHONE_TOP = 0.33f
        const val EARPHONE_BOTTOM = 0.50f
        const val EARPHONE_LEFT_MAX = 0.40f  //左耳机在画面左侧偏中，不再贴最左0.15
        const val EARPHONE_RIGHT_MIN = 0.60f //右耳机在画面右侧偏中，不再贴最右0.85

        // ===== 胸部：衣服露肩区域，整体大幅向下挪 0.46~0.72 =====
        const val CHEST_TOP = 0.52f
        const val CHEST_BOTTOM = 0.78f
        const val CHEST_LEFT = 0.18f
        const val CHEST_RIGHT = 0.82f

        // ===== 气泡相关（不用改）=====
        const val BUBBLE_DURATION = 2500L
        const val BUBBLE_OFFSET_Y_DP = -60

        // ===== 音乐氛围：随乐摇摆时冒音符 =====
        /** 音符冒出间隔 */
        const val NOTE_EMIT_INTERVAL_MS = 600L

        // ===== 触碰角色产出「円」 =====
        /** 每次触碰产出的金额与每日上限 */
        const val TOUCH_YEN = 1
        const val TOUCH_YEN_DAILY_CAP = 10
        /** 触碰产币冷却，避免连点刷币 */
        const val TOUCH_EARN_COOLDOWN_MS = 3000L
        /** 每日首次进入主界面的「见面礼」 */
        const val DAILY_OPEN_YEN = 5

        /** 每日首次摸头的好感产出（与钱包计数器同一套「今天最多 N 次」机制） */
        private const val COUNTER_TOUCH_AFFECTION = "touch_aff"

        /** 「今天摸够了」出口台词每天只提示一次 */
        private const val COUNTER_TOUCH_EXIT = "touch_exit"

        private const val COUNTER_TOUCH_YEN = "touch_yen"
        private const val COUNTER_DAILY_OPEN = "daily_open"
    }

    // ==================== 视图组件 ====================
    private lateinit var ivCharacter: ImageView
    private lateinit var characterContainer: FrameLayout
    private lateinit var btnStartChat: Button
    private lateinit var btnSettings: ImageView
    private lateinit var cardCharacterInfo: CardView
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvAffinity: TextView
    private lateinit var tvOnlineTime: TextView
    // 「円」钱包入口：展示余额，点击打开投喂 / 送礼面板
    private lateinit var tvYen: TextView
    private lateinit var btnGame: ImageView
    private lateinit var btnDiary: ImageView
    private lateinit var btnTimer: ImageView
    private lateinit var btnMusic: ImageView
    private lateinit var btnStory: ImageView
    // 签到入口：layout / layout-land / layout-sw600dp 三套布局均已包含这些控件
    private lateinit var cardSignIn: CardView
    private lateinit var tvSignInText: TextView
    private lateinit var btnSignInEntry: LinearLayout
    // 纪念日入口：与签到卡片同套布局，三份 activity_main.xml 均已包含
    private lateinit var cardAnniversary: CardView
    private lateinit var tvAnniversaryText: TextView
    private lateinit var btnAnniversaryEntry: LinearLayout
    // 礼物 / 投喂入口：与签到、纪念日同套卡片布局，三份 activity_main.xml 均已包含
    private lateinit var btnGiftEntry: LinearLayout
    // 好感度旁的小红点：仅提示「有新剧情」，不可单独点击（点角色卡进详情页看小剧场）
    private lateinit var btnAffectionEntry: FrameLayout
    // 每日任务入口卡片：图标 + 「每日任务 n/6」小字，点开进入每日任务页
    private lateinit var cardDailyTask: CardView
    private lateinit var tvDailyTaskTitle: TextView
    /** 每日任务小红点：全部完成且奖励未领取时点亮 */
    private lateinit var dotDailyTaskReward: View
    /** 循环指引的高亮动画（alpha 脉冲）：切指引 / 触摸角色时取消，避免叠加 */
    private var loopHighlight: ValueAnimator? = null
    /** 里程碑「有话想说」提示本次进程内只弹一次，避免每次回主页都打断 */
    private var milestoneNudged = false

    /** 立绘整体变换控制器（歪头 / 害羞 / 开心 / 凑近 / 随乐摇摆） */
    private lateinit var motion: CharacterMotion
    private var timeUpdateHandler: Handler? = null
    // 音乐摇摆轮询：音乐由前台 Service 播放，可能在通知栏被暂停/停止，故定时同步状态
    private var swayPollHandler: Handler? = null
    private var currentBubble: TextView? = null
    private val heartViews = mutableListOf<ImageView>()
    /** 上次触碰角色产币的时间戳：加冷却，避免连点刷币 */
    private var lastTouchEarnAt = 0L

    // ==================== 音乐氛围（音符跳动） ====================
    /** 音乐播放时定时冒出音符的 Handler */
    private var noteEmitHandler: Handler? = null
    /** 上一轮轮询是否在播放，用于捕捉「开始播放」的瞬间 */
    private var wasMusicPlaying = false

    // ==================== 音乐功能 ====================
    // OpenDocument launcher：选完本地音乐文件后立即丢给 MusicService 播放
    private val openAudioLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val displayName = queryDisplayName(uri) ?: "本地音乐"
            MusicManager.startLocalFile(this, uri, displayName)
        }
    }

    // 音频权限请求 launcher：用户拒绝时弹 toast，不崩溃
    private val audioPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
        if (granted) {
            openAudioLauncher.launch(arrayOf("audio/*"))
        } else {
            showToast("需要音频访问权限才能选择本地音乐")
        }
    }

    private val colors: IntArray by lazy {
        resources.obtainTypedArray(R.array.eden_greeting_colors).let { ta ->
            val arr = IntArray(ta.length()) { ta.getColor(it, 0) }
            ta.recycle()
            arr
        }
    }

    private val timeUpdateRunnable = object : Runnable {
        override fun run() {
            updateCurrentTime()
            timeUpdateHandler?.postDelayed(this, 60000)
        }
    }

    // 每秒同步一次「音乐是否在播」，驱动立绘随乐摇摆（音乐在通知栏也能被暂停/停止）
    private val swayPollRunnable = object : Runnable {
        override fun run() {
            val playing = MusicService.isPlayingNow
            if (::motion.isInitialized) motion.setSway(playing)
            updateMusicMood(playing)
            swayPollHandler?.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 登录门：首次启动（未登录）时先进入登录界面，登录后免登录直接进入主界面。
        AuthManager.init(this)
        if (!AuthManager.isLoggedIn(this)) {
            startActivity(android.content.Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        // 注册「想你」推送每日随机时段精确闹钟（幂等，重复调用安全）。
        // 用 AlarmManager 保证每日 12:00–20:00 之间随机一个时间点触发，
        // 时间和内容都不固定，避免「机械准点」；设备重启后由 BootReceiver 重新设闹钟。
        GreetingScheduler.schedule(this)

        initViews()
        setupListeners()
        startAnimations()
        startTimeUpdates()

        // 登录/启动后：从后端拉取该用户的业务数据到本地缓存，拉取完成后刷新界面展示。
        SyncManager.syncOnLogin(this) {
            refreshAffectionAndCompany()
            refreshSignInStatus()
            refreshAnniversaryStatus()
            // 拉取到的签到状态可能改变每日任务进度，同步刷新入口卡片
            refreshDailyTask()
        }
    }

    override fun onResume() {
        super.onResume()
        // 每日首次进入主界面：发「见面礼」円（tryDailyGrant 保证每天只发一次）
        if (WalletManager.tryDailyGrant(this, COUNTER_DAILY_OPEN, 1) == 1) {
            WalletManager.grant(this, DAILY_OPEN_YEN)
        }
        // 「见面」已从任务清单移除：进主页不再自动标记任何任务
        refreshAffectionAndCompany()
        refreshSignInStatus()
        refreshAnniversaryStatus()
        refreshDailyTask()
        // 主页气泡互斥，优先级：里程碑提示 > 循环指引 > 每日自言自语，避免几句台词在同一时段抢戏
        if (!maybeNudgeMilestone()) {
            if (!maybeGuideLoop()) maybeShowDailySoliloquy()
        }
        startSwayPolling()
    }

    override fun onPause() {
        // 页面不可见时停掉摇摆，避免后台空转耗电
        swayPollHandler?.removeCallbacks(swayPollRunnable)
        swayPollHandler = null
        if (::motion.isInitialized) motion.setSway(false)
        // 离开页面：停掉音符与循环高亮，避免后台空转
        stopMusicNotes()
        cancelLoopHighlight()
        wasMusicPlaying = false
        super.onPause()
    }

    private fun startSwayPolling() {
        if (!::motion.isInitialized) return
        swayPollHandler = Handler(Looper.getMainLooper())
        swayPollHandler?.post(swayPollRunnable)
    }

    private fun initViews() {
        ivCharacter = findViewById(R.id.iv_character)
        characterContainer = findViewById(R.id.character_container)
        btnStartChat = findViewById(R.id.btn_start_chat)
        btnSettings = findViewById(R.id.btn_settings)
        cardCharacterInfo = findViewById(R.id.card_character_info)
        tvCurrentTime = findViewById(R.id.tv_current_time)
        tvAffinity = findViewById(R.id.tv_affinity)
        tvOnlineTime = findViewById(R.id.tv_online_time)
        tvYen = findViewById(R.id.tv_yen)

        btnGame = findViewById(R.id.btn_game)
        btnDiary = findViewById(R.id.btn_diary)
        btnTimer = findViewById(R.id.btn_timer)
        btnMusic = findViewById(R.id.btn_music)
        btnStory = findViewById(R.id.btn_story)
        cardSignIn = findViewById(R.id.card_sign_in)
        tvSignInText = findViewById(R.id.tv_sign_in_text)
        btnSignInEntry = findViewById(R.id.btn_sign_in_entry)
        cardAnniversary = findViewById(R.id.card_anniversary)
        tvAnniversaryText = findViewById(R.id.tv_anniversary_text)
        btnAnniversaryEntry = findViewById(R.id.btn_anniversary_entry)
        btnGiftEntry = findViewById(R.id.btn_gift_entry)
        btnAffectionEntry = findViewById(R.id.btn_affection_entry)
        cardDailyTask = findViewById(R.id.card_daily_task)
        tvDailyTaskTitle = findViewById(R.id.tv_daily_task_title)
        dotDailyTaskReward = findViewById(R.id.dot_daily_task_reward)

        motion = CharacterMotion(ivCharacter)
        updateCurrentTime()
    }

    private fun setupListeners() {
        // 替换 onClickListener 为 OnTouchListener，获取触摸坐标
        ivCharacter.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                handleCharacterTouch(view, event.x, event.y)
            }
            true
        }

        btnStartChat.setOnClickListener { onStartChatClicked() }
        btnSettings.setOnClickListener { onSettingsClicked() }
        cardCharacterInfo.setOnClickListener { onCharacterInfoClicked() }
        btnSignInEntry.setOnClickListener { showSignCalendarDialog() }
        btnAnniversaryEntry.setOnClickListener { showAnniversaryDialog() }
        // 点击礼物卡片进入投喂 / 送礼面板
        btnGiftEntry.setOnClickListener { showGiftPicker() }
        // 点击每日任务入口卡片，进入每日任务页查看 / 领取奖励
        cardDailyTask.setOnClickListener {
            startActivity(android.content.Intent(this, DailyTaskActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
        // 好感度旁的红点只做提示，不再是独立入口；点整张角色卡即打开角色详情，
        // 小剧场统一从详情页里的「好感小剧场」进入

        btnGame.setOnClickListener { onGameClicked() }
        btnDiary.setOnClickListener {
            startActivity(android.content.Intent(this, DiaryActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
        btnTimer.setOnClickListener {
            startActivity(android.content.Intent(this, FocusTimerActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
        btnMusic.setOnClickListener { showMusicPicker() }
        btnStory.setOnClickListener {
            startActivity(android.content.Intent(this, StoryHomeActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    /**
     * 左下角游戏入口：底部弹出粉色小游戏选择面板，当前有「扫雷」「五子棋」「象棋」三个。
     */
    private fun onGameClicked() {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_game_picker, null)
        sheet.setContentView(view)
        // 去掉 BottomSheet 默认白底，让自定义粉色圆角面板生效
        (view.parent as? View)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        view.findViewById<View>(R.id.cardGameMinesweeper)
            ?.setOnClickListener { openGame(sheet, MinesweeperActivity::class.java) }
        view.findViewById<View>(R.id.cardGameGomoku)
            ?.setOnClickListener { openGame(sheet, GomokuActivity::class.java) }
        view.findViewById<View>(R.id.cardGameXiangqi)
            ?.setOnClickListener { openGame(sheet, XiangqiActivity::class.java) }

        sheet.show()
    }

    /** 关闭选择面板并进入对应小游戏。 */
    private fun openGame(sheet: BottomSheetDialog, target: Class<*>) {
        sheet.dismiss()
        startActivity(android.content.Intent(this, target))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    /**
     * 投喂 / 送礼面板：底部弹出，按 饮料 / 食物 / 礼物 分组列出物品。
     * 点击即扣「円」并结算好感；成功时头顶气泡播放三玖的专属回应，并刷新余额 / 限次状态。
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
                    setPadding(0, dpToPx(10), 0, 0)
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
                        showHeadBubble(result.line)
                        if (result.crossedMilestone) btnAffectionEntry.visibility = View.VISIBLE
                        refreshRow()
                        refreshBalance()
                        refreshAffectionAndCompany()
                        // 投喂完成 = 每日任务「投喂」一项达成
                        DailyLoopManager.markDone(this, LoopStep.FEED)
                        refreshDailyTask()
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

    // ==================== 音乐功能 ====================

    /**
     * 弹底部菜单：播放内置环境音 / 选择本地音乐 / 停止。
     * "停止" 项仅在 MusicService 真的存活时显示（粗略判断：通知 ID 2001 存在）。
     */
    private fun showMusicPicker() {
        // 申请通知权限（前台通知需要），失败不影响 BGM 本身
        ensureNotificationPermission()
        val dialog = MusicPickerDialog().apply {
            setListener(this@MainActivity)
            // 简化：先一直显示停止项，重复点也无所谓，MusicManager.stop 自身幂等
            showStopItem(true)
        }
        dialog.show(supportFragmentManager, "music_picker")
    }

    override fun onPickAmbient() {
        // 打开「环境音选择」卡片面板，让用户自由选择播放哪种环境音
        showAmbientPicker()
    }

    /**
     * 内置环境音选择面板：底部弹出卡片式界面，样式对齐小游戏选择面板。
     * 「默认」跟随天气/时间自动选择（照搬原有逻辑），其余直接播放指定氛围。
     */
    private fun showAmbientPicker() {
        ensureNotificationPermission()
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_ambient_picker, null)
        sheet.setContentView(view)
        // 去掉 BottomSheet 默认白底，让自定义粉色圆角面板生效
        (view.parent as? View)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        view.findViewById<View>(R.id.cardAmbientDefault)
            ?.setOnClickListener { startAmbientMode(sheet, null) }
        view.findViewById<View>(R.id.cardAmbientDay)
            ?.setOnClickListener { startAmbientMode(sheet, AmbientSynth.Mode.DAY) }
        view.findViewById<View>(R.id.cardAmbientRain)
            ?.setOnClickListener { startAmbientMode(sheet, AmbientSynth.Mode.RAIN) }
        view.findViewById<View>(R.id.cardAmbientSnow)
            ?.setOnClickListener { startAmbientMode(sheet, AmbientSynth.Mode.SNOW) }
        view.findViewById<View>(R.id.cardAmbientNight)
            ?.setOnClickListener { startAmbientMode(sheet, AmbientSynth.Mode.NIGHT) }

        sheet.show()
    }

    /** 关闭面板并播放指定氛围；mode 为 null 表示「默认」，按天气/时间自动选择。 */
    private fun startAmbientMode(sheet: BottomSheetDialog, mode: AmbientSynth.Mode?) {
        sheet.dismiss()
        if (mode == null) {
            // 走协程：MusicManager.startAmbientByWeather 是 suspend
            lifecycleScope.launch {
                MusicManager.startAmbientByWeather(this@MainActivity)
            }
        } else {
            MusicManager.startAmbient(this, mode)
        }
    }

    override fun onPickLocalFile() {
        // Android 13+ 走 READ_MEDIA_AUDIO；旧版回退 READ_EXTERNAL_STORAGE
        if (!hasAudioPermission()) {
            val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            audioPermLauncher.launch(perm)
        } else {
            openAudioLauncher.launch(arrayOf("audio/*"))
        }
    }

    override fun onPickStop() {
        MusicManager.stop(this)
    }

    private fun hasAudioPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 申请 POST_NOTIFICATIONS（Android 13+），失败不阻断 BGM 本身。
     * 范式参考 ChatActivity.requestNotificationPermissionIfNeeded。
     */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // 不阻塞用户操作，请求一次即可，结果不影响 BGM 启动
            @Suppress("DEPRECATION")
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200)
        }
    }

    /**
     * 从 SAF Uri 查询文件名显示在通知栏。
     * 失败返回 null，调用方用兜底文案。
     */
    private fun queryDisplayName(uri: Uri): String? {
        var name: String? = null
        val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && it.moveToFirst()) {
                name = it.getString(idx)
            }
        }
        return name
    }

    // ==================== 触摸处理 ====================
    private fun handleCharacterTouch(view: View, x: Float, y: Float) {
        // 移除之前的气泡
        currentBubble?.let {
            characterContainer.removeView(it)
        }

        val width = view.width.toFloat()
        val height = view.height.toFloat()
        if (width == 0f || height == 0f) return

        val ratioX = x / width
        val ratioY = y / height

        // 判断区域
        val line = when {
            // 耳机（挂在脖子上，下巴下方 ~ 胸口上方）：疑惑歪头
            ratioY >= EARPHONE_TOP && ratioY <= EARPHONE_BOTTOM &&
                    (ratioX <= EARPHONE_LEFT_MAX || ratioX >= EARPHONE_RIGHT_MIN) -> {
                motion.tiltHead()
                MikuPersona.EARPHONE_LINES.random()
            }
            // 头部：开心弹跳
            ratioY >= HEAD_TOP && ratioY <= HEAD_BOTTOM &&
                    ratioX >= HEAD_LEFT && ratioX <= HEAD_RIGHT -> {
                // 确保 view 宽高已测量，避免坐标 0 导致爱心从左上角飞出
                if (view.width > 0 && view.height > 0) {
                    spawnHearts(view, x, y)
                } else {
                    // 若未测量，延迟到下一帧再触发（极少发生）
                    view.post { spawnHearts(view, x, y) }
                }
                motion.happyBounce()
                MikuPersona.HEAD_LINES.random()
            }
            // 胸部（下移，原耳机位置下方）：害羞发抖 + 脸红
            ratioY >= CHEST_TOP && ratioY <= CHEST_BOTTOM &&
                    ratioX >= CHEST_LEFT && ratioX <= CHEST_RIGHT -> {
                motion.shyShake()
                MikuPersona.CHEST_LINES.random()
            }
            // 其他：凑近倾听
            else -> {
                motion.leanIn()
                MikuPersona.OTHER_LINES.random()
            }
        }

        showBubble(x, y, line)
        cancelLoopHighlight()
        onTouchedLoop()
        earnTouchYen()
    }

    /**
     * 摸头接入每日任务：首次摸头标记 [LoopStep.TOUCH] 完成并 +1 好感（每天一次），
     * 让「摸摸」不再是无产出的死胡同。
     */
    private fun onTouchedLoop() {
        if (!DailyLoopManager.markDone(this, LoopStep.TOUCH)) return
        refreshDailyTask()
        if (WalletManager.tryDailyGrant(this, COUNTER_TOUCH_AFFECTION, 1) == 1) {
            val (newValue, crossed) = AffectionManager.increase(this, 1)
            if (crossed) MilestoneManager.onCrossed(this, newValue)
            refreshAffectionAndCompany()
        }
    }

    /**
     * 触碰角色产出「円」：带 3 秒冷却 + 每日上限，避免连点刷币。
     * 冷却中静默跳过；今日已领满时用一句指向聊天的台词把用户引向下一个动作。
     */
    private fun earnTouchYen() {
        val now = System.currentTimeMillis()
        if (now - lastTouchEarnAt < TOUCH_EARN_COOLDOWN_MS) return
        lastTouchEarnAt = now
        val added = WalletManager.earn(this, COUNTER_TOUCH_YEN, TOUCH_YEN, TOUCH_YEN_DAILY_CAP)
        if (added > 0) {
            refreshAffectionAndCompany()
            return
        }
        // 今天摸够了：出口台词每天只提一次，把「摸头」这一步引向聊天
        if (WalletManager.dailyValue(this, COUNTER_TOUCH_YEN) >= TOUCH_YEN_DAILY_CAP &&
            WalletManager.tryDailyGrant(this, COUNTER_TOUCH_EXIT, 1) == 1
        ) {
            ivCharacter.postDelayed({
                if (isFinishing || isDestroyed) return@postDelayed
                val line = MikuPersona.LOOP_GUIDE_LINES[LoopStep.CHAT.id]?.random() ?: return@postDelayed
                showHeadBubble(line)
            }, 900L)
        }
    }

    private fun showBubble(rawX: Float, rawY: Float, text: String) {
        val bubble = TextView(this).apply {
            this.text = text
            setTextColor(edenColor(R.attr.edenColorTextPrimary, R.color.eden_text_primary))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dpToPx(14), dpToPx(8), dpToPx(14), dpToPx(8))
            background = getDrawable(R.drawable.bg_bubble_touch)
            maxWidth = dpToPx(200)
        }

        // 测量尺寸，用于定位
        bubble.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val bubbleWidth = bubble.measuredWidth
        val bubbleHeight = bubble.measuredHeight

        // 触摸点转换到 FrameLayout 坐标系
        // ivCharacter 是 characterContainer 的直接子 View，所以应使用它在父容器内的 left/top
        // （与 spawnHearts 一致的父坐标系）。若误用 getLocationInWindow 的窗口坐标，
        // 会把状态栏/标题栏等窗口偏移一并叠加到 leftMargin/topMargin 上，导致气泡错位。
        val touchXInParent = ivCharacter.left + rawX
        val touchYInParent = ivCharacter.top + rawY

        // 计算气泡位置
        var left = touchXInParent - bubbleWidth / 2
        var top = touchYInParent + dpToPx(BUBBLE_OFFSET_Y_DP)

        // 防止超出屏幕边界
        val parentWidth = characterContainer.width
        if (left < 0) left = dpToPx(8).toFloat()
        if (left + bubbleWidth > parentWidth) left = (parentWidth - bubbleWidth - dpToPx(8)).toFloat()

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = left.toInt()
            topMargin = top.toInt()
        }

        bubble.layoutParams = params
        bubble.alpha = 0f
        characterContainer.addView(bubble)
        currentBubble = bubble

        // 渐显
        bubble.animate().alpha(1f).setDuration(200).start()

        // 延时渐隐并移除
        bubble.postDelayed({
            bubble.animate().alpha(0f).setDuration(300).withEndAction {
                characterContainer.removeView(bubble)
                if (currentBubble == bubble) currentBubble = null
            }.start()
        }, BUBBLE_DURATION)
    }

    private fun spawnHearts(view: View, touchX: Float, touchY: Float) {
        val container = characterContainer
        if (container.width == 0 || container.height == 0) return

        // 获取角色 ImageView 在容器中的位置
        val viewLeft = view.left.toFloat()
        val viewTop = view.top.toFloat()

        // 触摸点在容器中的绝对坐标
        val centerX = viewLeft + touchX
        val centerY = viewTop + touchY

        // 爱心生成范围半径（dp转px）
        val radius = dpToPx(25)

        // 清除旧爱心
        heartViews.forEach { container.removeView(it) }
        heartViews.clear()

        val count = Random.nextInt(8, 12)
        val drawable = resources.getDrawable(R.drawable.ic_heart, null)

        for (i in 0 until count) {
            // 在触摸点周围随机偏移生成
            val offsetX = (Random.nextFloat() - 0.5f) * radius * 2
            val offsetY = (Random.nextFloat() - 0.5f) * radius * 2
            val px = centerX + offsetX
            val py = centerY + offsetY

            val size = Random.nextInt(16, 28)
            val imageView = ImageView(this).apply {
                setImageDrawable(drawable)
                alpha = 0.6f
                setColorFilter(colors.random(), PorterDuff.Mode.SRC_IN)
                val sizePx = dpToPx(size)
                layoutParams = FrameLayout.LayoutParams(sizePx, sizePx).apply {
                    leftMargin = (px - sizePx / 2).toInt()
                    topMargin = (py - sizePx / 2).toInt()
                }
            }
            container.addView(imageView)
            heartViews.add(imageView)

            // 飘散方向：随机角度（-80° ~ 80°，基本向上和两侧）
            val angle = (Random.nextFloat() - 0.5f) * 160f * Math.PI.toFloat() / 180f
            val distance = Random.nextFloat() * dpToPx(100) + dpToPx(50)
            val dx = sin(angle.toDouble()).toFloat() * distance
            val dy = -cos(angle.toDouble()).toFloat() * distance * 0.8f  // 主要向上

            val rotation = Random.nextFloat() * 360f - 180f
            val duration = 2000L + Random.nextLong(500)

            // 使用 translationX/Y 做相对位移，杜绝坐标错误
            imageView.animate()
                .translationX(dx)
                .translationY(dy)
                .rotation(rotation)
                .setDuration(duration)
                .setStartDelay(Random.nextLong(100))
                .start()

            // 透明度闪烁动画
            val alphaAnim = ValueAnimator.ofFloat(0.5f, 0.9f, 0.3f, 0.8f, 0.2f, 0.7f, 0.5f)
            alphaAnim.duration = duration
            alphaAnim.startDelay = Random.nextLong(50)
            alphaAnim.addUpdateListener { anim ->
                imageView.alpha = anim.animatedValue as Float
            }
            alphaAnim.start()

            // 动画结束后移除
            imageView.postDelayed({
                container.removeView(imageView)
                heartViews.remove(imageView)
            }, duration + 400)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    // ==================== 音乐氛围（音符跳动） ====================

    /**
     * 根据音乐播放状态刷新氛围：开始播放时冒音符；停止播放时收掉音符。
     * 由每秒一次的 [swayPollRunnable] 驱动。
     */
    private fun updateMusicMood(playing: Boolean) {
        if (playing) {
            if (!wasMusicPlaying) {
                startMusicNotes()
            }
        } else if (wasMusicPlaying) {
            stopMusicNotes()
        }
        wasMusicPlaying = playing
    }

    private val noteEmitRunnable = object : Runnable {
        override fun run() {
            spawnMusicNotes()
            noteEmitHandler?.postDelayed(this, NOTE_EMIT_INTERVAL_MS)
        }
    }

    private fun startMusicNotes() {
        if (noteEmitHandler != null) return
        noteEmitHandler = Handler(Looper.getMainLooper())
        noteEmitHandler?.post(noteEmitRunnable)
    }

    private fun stopMusicNotes() {
        noteEmitHandler?.removeCallbacks(noteEmitRunnable)
        noteEmitHandler = null
    }

    /** 在立绘周围冒出一两个音符，向上飘散并淡出，像跟着音乐轻轻哼唱 */
    private fun spawnMusicNotes() {
        val container = characterContainer
        if (container.width == 0 || container.height == 0) return
        val view = ivCharacter
        if (view.width == 0 || view.height == 0) return

        val centerX = view.left + view.width / 2f
        val centerY = view.top + view.height * 0.35f
        val radius = dpToPx(90)
        val glyphs = arrayOf("♪", "♫", "♩", "♬")

        repeat(Random.nextInt(1, 3)) {
            val note = TextView(this).apply {
                text = glyphs.random()
                setTextColor(colors.random())
                textSize = 16f + Random.nextFloat() * 10f
                gravity = Gravity.CENTER
                alpha = 0f
            }
            val px = centerX + (Random.nextFloat() - 0.5f) * radius * 2f
            val py = centerY + (Random.nextFloat() - 0.5f) * radius
            val box = dpToPx(36)
            note.layoutParams = FrameLayout.LayoutParams(box, box).apply {
                leftMargin = (px - box / 2f).toInt()
                topMargin = (py - box / 2f).toInt()
            }
            container.addView(note)

            val duration = 1500L
            note.animate()
                .translationX((Random.nextFloat() - 0.5f) * dpToPx(60))
                .translationY(-dpToPx(90) - Random.nextFloat() * dpToPx(70))
                .rotation((Random.nextFloat() - 0.5f) * 40f)
                .setDuration(duration)
                .setInterpolator(DecelerateInterpolator())
                .start()

            val fade = ValueAnimator.ofFloat(0f, 1f, 0.9f, 0f).apply { this.duration = duration }
            fade.addUpdateListener { anim -> note.alpha = anim.animatedValue as Float }
            fade.start()

            note.postDelayed({ container.removeView(note) }, duration + 100)
        }
    }

    // ==================== 原有功能保持 ====================
    private fun onStartChatClicked() {
        btnStartChat.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100)
            .withEndAction {
                btnStartChat.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                val intent = android.content.Intent(this, ChatActivity::class.java)
                startActivity(intent)
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }.start()
    }

    private fun onSettingsClicked() {
        btnSettings.animate().rotationBy(360f).setDuration(300).start()
        startActivity(android.content.Intent(this, SettingsActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun onCharacterInfoClicked() {
        cardCharacterInfo.animate().translationZ(20f).setDuration(100)
            .withEndAction {
                cardCharacterInfo.animate().translationZ(0f).setDuration(100).start()
            }.start()

        // 首次聊天时间从 Room 异步取（与纪念日卡片同一模式），取到后再弹详情，避免主线程开 DB
        lifecycleScope.launch {
            val firstChatTime = ChatRepository(this@MainActivity).firstChatTime(MikuPersona.ID)
            CharacterDetailDialog(
                context = this@MainActivity,
                firstChatTime = firstChatTime,
                onOpenChat = {
                    startActivity(android.content.Intent(this@MainActivity, ChatActivity::class.java))
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                },
                onOpenDiary = {
                    startActivity(android.content.Intent(this@MainActivity, DiaryActivity::class.java))
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                }
            ).show()
        }
    }

    private fun startAnimations() {
        // 呼吸等常驻变换统一交由 CharacterMotion 管理，避免与瞬时动作抢属性
        motion.start()
    }

    private fun startTimeUpdates() {
        timeUpdateHandler = Handler(Looper.getMainLooper())
        timeUpdateHandler?.post(timeUpdateRunnable)
    }

    private fun updateCurrentTime() {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        tvCurrentTime.text = sdf.format(Date())
    }

    private fun refreshAffectionAndCompany() {
        val affection = AffectionManager.getAffection(this)
        // 好感度不设上限，直接展示数值
        tvAffinity.text = "$affection"

        // 余额入口：展示当前「円」
        tvYen.text = "礼物 · ${WalletManager.getBalance(this)} 円"

        // 有新剧情（跨过里程碑但还没看）时点亮小红点提示
        btnAffectionEntry.visibility =
            if (MilestoneManager.hasUnviewed(this)) View.VISIBLE else View.GONE

        // 陪伴时长统一由 CompanyTimeManager 管理（秒级，聊天页每 30 秒 flush 一次）
        val seconds = CompanyTimeManager.getTodaySeconds(this)
        tvOnlineTime.text = "今日陪伴：${CompanyTimeManager.format(seconds)}"
    }

    private fun refreshSignInStatus() {
        if (SignInManager.isTodaySigned(this)) {
            tvSignInText.text = "已签到"
            tvSignInText.setTextColor(edenColor(R.attr.edenColorTextSecondary, R.color.eden_text_secondary))
            // 已签到即视为每日任务「签到」一项达成（幂等，跨天自动重置）
            if (DailyLoopManager.markDone(this, LoopStep.SIGN_IN)) refreshDailyTask()
        } else {
            tvSignInText.text = getString(R.string.main_sign_in)
            tvSignInText.setTextColor(edenColor(R.attr.edenColorAccent, R.color.eden_accent))
        }
    }

    private fun showSignCalendarDialog() {
        val dialog = SignCalendarDialog(this)
        dialog.show()
        dialog.setOnDismissListener {
            refreshSignInStatus()
            refreshDailyTask()
            // 签到完成后（或用户主动关掉后）按最新进度给下一次指引
            maybeGuideLoop()
        }
    }

    // ==================== 纪念日 ====================

    /**
     * 刷新签到下方纪念日卡片文本。首次聊天时间从 Room 异步取，避免在主线程开 DB。
     * 命中 milestone / 生日 / 节日当天用粉色 + ♡ 高亮，平日灰色。
     */
    private fun refreshAnniversaryStatus() {
        lifecycleScope.launch {
            val firstChatTime = ChatRepository(this@MainActivity).firstChatTime(MikuPersona.ID)
            val days = AnniversaryManager.daysSinceStart(this@MainActivity, firstChatTime)
            val cal = java.util.Calendar.getInstance()
            val isHighlight = AnniversaryManager.isMilestoneDay(days) ||
                AnniversaryManager.isBirthdayToday(this@MainActivity, cal) ||
                FestivalCalendar.todayFestival(cal) != null
            tvAnniversaryText.text = when {
                days == null -> "还没开始"
                isHighlight -> "在一起 $days 天 ♡"
                else -> "在一起 $days 天"
            }
            tvAnniversaryText.setTextColor(
                if (isHighlight) edenColor(R.attr.edenColorAccent, R.color.eden_accent)
                else edenColor(R.attr.edenColorTextSecondary, R.color.eden_text_secondary)
            )
        }
    }

    /**
     * 点击纪念日卡片弹出详情。复用 [refreshAnniversaryStatus] 里取过的天数会过期，
     * 这里重新拉一次，保证对话框里展示的是最新数据。
     */
    private fun showAnniversaryDialog() {
        lifecycleScope.launch {
            val firstChatTime = ChatRepository(this@MainActivity).firstChatTime(MikuPersona.ID)
            val days = AnniversaryManager.daysSinceStart(this@MainActivity, firstChatTime)
            AnniversaryDialog(this@MainActivity, days) { refreshAnniversaryStatus() }.show()
        }
    }

    // ==================== 每日随机小剧场 ====================
    /**
     * 进 App 概率触发三玖「自言自语」两三句，给用户「她有自己的生活」的感觉。
     *
     * 触发条件（同时满足）：
     * 1. 今天已签到——未签到时让位给签到弹窗，签到完下次进 App 才有机会遇到；
     * 2. 今天还没触发过小剧场（用 SharedPreferences 记 yyyy-MM-dd）；
     * 3. 60% 概率命中——未命中不写日期，当天再次进 App 仍有机会，避免「错过今天就没了」的挫败。
     *
     * 命中后逐句延时显示在角色头顶，每句间隔 3s（一句显示 2.5s + 0.3s 渐隐刚好衔接）。
     * 每段 2-3 句，台词池见 [com.example.eden.character.MikuPersona.DAILY_SOLILOQUIES]。
     *
     * @return 是否真的触发了自言自语（用于和「里程碑提示」互斥，避免两句台词抢戏）。
     */
    private fun maybeShowDailySoliloquy(): Boolean {
        if (!SignInManager.isTodaySigned(this)) return false
        val prefs = EdenStorage.prefs(this, "daily_soliloquy_shown")
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        if (prefs.getString("shown_date", "") == today) return false
        if (Random.nextInt(100) >= 60) return false
        // ivCharacter 未测量好时（onResume 极早调用）跳过本次，下次进 App 再说
        // 注意：必须在写日期之前 return，否则今天的机会会被「吞掉」
        if (ivCharacter.width == 0 || ivCharacter.height == 0) return false

        prefs.edit().putString("shown_date", today).apply()

        val soliloquy = MikuPersona.DAILY_SOLILOQUIES.random()
        soliloquy.forEachIndexed { index, line ->
            ivCharacter.postDelayed({
                if (isFinishing || isDestroyed) return@postDelayed
                showHeadBubble(line)
            }, index * 3000L)
        }
        return true
    }

    /**
     * 好感度跨过里程碑后进主页时，让三玖做一次「有话想说」的即时提示 + 开心弹跳，
     * 把玩家注意力引到好感度旁的红点上。仅在本次进程内触发一次，避免每次回主页都打断。
     * 立绘尚未测量好时跳过，等下次进主页再补。
     *
     * @return 是否真的给出了提示（供主页气泡互斥判断）。
     */
    private fun maybeNudgeMilestone(): Boolean {
        if (milestoneNudged) return false
        if (!MilestoneManager.hasUnviewed(this)) return false
        if (ivCharacter.width == 0 || ivCharacter.height == 0) return false
        milestoneNudged = true
        ivCharacter.post {
            if (isFinishing || isDestroyed) return@post
            motion.happyBounce()
            showHeadBubble("……那个，等你忙完，我有点话想跟你说。")
        }
        return true
    }

    // ==================== 每日任务（入口卡片 + 指引） ====================

    /**
     * 刷新主页「每日任务」入口卡片：更新「每日任务 n/6」文案；
     * 六项全部完成且奖励尚未领取时点亮小红点，提示用户进任务页领奖。
     */
    private fun refreshDailyTask() {
        if (!::tvDailyTaskTitle.isInitialized) return
        val (count, total) = DailyLoopManager.todayProgress(this)
        tvDailyTaskTitle.text = getString(R.string.main_daily_task_progress, count, total)
        val readyToClaim = count >= total && !DailyLoopManager.isRewardClaimed(this)
        dotDailyTaskReward.visibility = if (readyToClaim) View.VISIBLE else View.GONE
    }

    /**
     * 回到主页时的任务兜底指引：找出今天第一项还没完成的任务，用一句台词 + 脉冲高亮
     * 把用户引过去。同一项任务当天只指引一次（跨天由 [DailyLoopManager] 自动重置），
     * 避免每次回主页都重复弹同一句。
     *
     * @return 是否真的给出了指引（供主页气泡互斥判断）。
     */
    private fun maybeGuideLoop(): Boolean {
        val next = DailyLoopManager.nextStep(this) ?: return false
        if (DailyLoopManager.lastGuided(this) == next.id) return false
        if (ivCharacter.width == 0 || ivCharacter.height == 0) return false
        ivCharacter.post {
            if (isFinishing || isDestroyed) return@post
            guideToStep(next)
        }
        return true
    }

    /** 就地指引：一句引导台词 + 目标入口脉冲高亮，并记下「已指引过该节点」。 */
    private fun guideToStep(step: LoopStep) {
        val line = MikuPersona.LOOP_GUIDE_LINES[step.id]?.random() ?: return
        DailyLoopManager.markGuided(this, step)
        showHeadBubble(line)
        highlightLoopAnchor(step)
    }

    /**
     * 对某项任务对应的主页入口做 alpha 脉冲高亮。
     * 立绘的 scale / rotation / translation / colorFilter 已被 [CharacterMotion] 独占，
     * 只有 alpha 空闲，因此统一只用 alpha 脉冲，避免属性互相抢。
     */
    private fun highlightLoopAnchor(step: LoopStep) {
        val anchor = when (step) {
            LoopStep.SIGN_IN -> cardSignIn
            LoopStep.TOUCH -> ivCharacter
            LoopStep.GAME -> btnGame
            LoopStep.CHAT -> btnStartChat
            LoopStep.FOCUS -> btnTimer
            LoopStep.FEED -> btnGiftEntry
        }
        cancelLoopHighlight()
        val original = anchor.alpha
        loopHighlight = ValueAnimator.ofFloat(original, 0.4f, original, 0.4f, original).apply {
            duration = 1600L
            addUpdateListener { anchor.alpha = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    anchor.alpha = original
                    loopHighlight = null
                }
            })
            start()
        }
    }

    private fun cancelLoopHighlight() {
        loopHighlight?.cancel()
        loopHighlight = null
    }

    /**
     * 在角色头顶显示一句气泡，样式与触摸气泡一致。
     * 复用 [currentBubble] 引用：新气泡会顶替旧的，逐句顶替形成「自言自语」节奏。
     */
    private fun showHeadBubble(text: String) {
        currentBubble?.let { characterContainer.removeView(it) }
        currentBubble = null

        val bubble = TextView(this).apply {
            this.text = text
            setTextColor(edenColor(R.attr.edenColorTextPrimary, R.color.eden_text_primary))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dpToPx(14), dpToPx(8), dpToPx(14), dpToPx(8))
            background = getDrawable(R.drawable.bg_bubble_touch)
            maxWidth = dpToPx(200)
        }
        bubble.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val bw = bubble.measuredWidth
        val bh = bubble.measuredHeight

        // 水平居中于角色，垂直贴头顶上方
        val cx = ivCharacter.left + ivCharacter.width / 2
        var left = (cx - bw / 2).toFloat()
        val top = ivCharacter.top + (ivCharacter.height * 0.15f).toInt() - bh - dpToPx(8)

        val parentWidth = characterContainer.width
        if (parentWidth > 0) {
            if (left < 0) left = dpToPx(8).toFloat()
            if (left + bw > parentWidth) left = (parentWidth - bw - dpToPx(8)).toFloat()
        }

        bubble.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = left.toInt()
            topMargin = top
        }

        bubble.alpha = 0f
        characterContainer.addView(bubble)
        currentBubble = bubble

        bubble.animate().alpha(1f).setDuration(200).start()
        bubble.postDelayed({
            bubble.animate().alpha(0f).setDuration(300).withEndAction {
                characterContainer.removeView(bubble)
                if (currentBubble == bubble) currentBubble = null
            }.start()
        }, BUBBLE_DURATION)
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        if (::motion.isInitialized) motion.release()
        timeUpdateHandler?.removeCallbacks(timeUpdateRunnable)
        swayPollHandler?.removeCallbacks(swayPollRunnable)
        stopMusicNotes()
        super.onDestroy()
    }
}