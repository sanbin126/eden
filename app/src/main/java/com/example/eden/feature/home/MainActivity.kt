package com.example.eden.feature.home


import com.example.eden.R
import com.example.eden.core.session.SessionManager
import com.example.eden.core.ui.BaseActivity
import com.example.eden.domain.economy.EconomyManager
import com.example.eden.feature.auth.LoginActivity
import com.example.eden.feature.daily.DailyLoopManager
import com.example.eden.feature.diary.DiaryActivity
import com.example.eden.feature.focus.FocusTimerActivity
import com.example.eden.feature.greeting.GreetingScheduler
import com.example.eden.feature.music.AudioManager
import com.example.eden.feature.music.MusicPickerDialog
import com.example.eden.feature.music.MusicService
import com.example.eden.feature.story.StoryHomeActivity
import com.example.eden.ui.theme.edenColorArray
import android.animation.ValueAnimator
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.cardview.widget.CardView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : BaseActivity(), MusicPickerDialog.Listener {

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
        internal const val COUNTER_TOUCH_AFFECTION = "touch_aff"

        /** 「今天摸够了」出口台词每天只提示一次 */
        internal const val COUNTER_TOUCH_EXIT = "touch_exit"

        internal const val COUNTER_TOUCH_YEN = "touch_yen"

        private const val COUNTER_DAILY_OPEN = "daily_open"
    }

    // ==================== 视图组件 ====================
    internal lateinit var ivCharacter: ImageView
    internal lateinit var characterContainer: FrameLayout
    internal lateinit var btnStartChat: Button
    internal lateinit var btnSettings: ImageView
    internal lateinit var cardCharacterInfo: CardView
    internal lateinit var tvAffinity: TextView
    internal lateinit var tvOnlineTime: TextView
    // 「円」钱包入口：展示余额，点击打开投喂 / 送礼面板
    internal lateinit var tvYen: TextView
    internal lateinit var btnGame: ImageView
    internal lateinit var btnDiary: ImageView
    internal lateinit var btnTimer: ImageView
    internal lateinit var btnMusic: ImageView
    internal lateinit var btnStory: ImageView
    // 签到入口：layout / layout-land / layout-sw600dp 三套布局均已包含这些控件
    internal lateinit var cardSignIn: CardView
    internal lateinit var tvSignInText: TextView
    internal lateinit var btnSignInEntry: LinearLayout
    // 纪念日入口：与签到卡片同套布局，三份 activity_main.xml 均已包含
    internal lateinit var cardAnniversary: CardView
    internal lateinit var tvAnniversaryText: TextView
    internal lateinit var btnAnniversaryEntry: LinearLayout
    // 礼物 / 投喂入口：与签到、纪念日同套卡片布局，三份 activity_main.xml 均已包含
    internal lateinit var btnGiftEntry: LinearLayout
    // 好感度旁的小红点：仅提示「有新剧情」，不可单独点击（点角色卡进详情页看小剧场）
    internal lateinit var btnAffectionEntry: FrameLayout
    // 每日任务入口卡片：图标 + 「每日任务 n/6」小字，点开进入每日任务页
    internal lateinit var cardDailyTask: CardView
    internal lateinit var tvDailyTaskTitle: TextView
    /** 每日任务小红点：全部完成且奖励未领取时点亮 */
    internal lateinit var dotDailyTaskReward: View
    /** 循环指引的高亮动画（alpha 脉冲）：切指引 / 触摸角色时取消，避免叠加 */
    internal var loopHighlight: ValueAnimator? = null
    /** 里程碑「有话想说」提示本次进程内只弹一次，避免每次回主页都打断 */
    internal var milestoneNudged = false

    /** 立绘整体变换控制器（歪头 / 害羞 / 开心 / 凑近 / 随乐摇摆） */
    internal lateinit var motion: CharacterMotion
    // 音乐摇摆轮询：音乐由前台 Service 播放，可能在通知栏被暂停/停止，故定时同步状态
    private var swayPollHandler: Handler? = null
    internal var currentBubble: TextView? = null
    internal val heartViews = mutableListOf<ImageView>()
    /** 上次触碰角色产币的时间戳：加冷却，避免连点刷币 */
    internal var lastTouchEarnAt = 0L

    // ==================== 音乐氛围（音符跳动） ====================
    /** 音乐播放时定时冒出音符的 Handler */
    internal var noteEmitHandler: Handler? = null
    /** 上一轮轮询是否在播放，用于捕捉「开始播放」的瞬间 */
    internal var wasMusicPlaying = false

    internal val noteEmitRunnable = object : Runnable {
        override fun run() {
            spawnMusicNotes()
            noteEmitHandler?.postDelayed(this, NOTE_EMIT_INTERVAL_MS)
        }
    }

    // ==================== 音乐功能 ====================
    // OpenDocument launcher：选完本地音乐文件后立即丢给 MusicService 播放
    private val openAudioLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val displayName = queryDisplayName(uri) ?: "本地音乐"
            AudioManager.startLocalFile(this, uri, displayName)
        }
    }

    internal val colors: IntArray by lazy {
        edenColorArray(R.attr.edenGreetingColors, R.array.eden_greeting_colors)
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
        SessionManager.init(this)
        if (!SessionManager.isLoggedIn(this)) {
            startActivity(android.content.Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        setupImmersiveTopBar()

        // 注册主动消息的每日精确闹钟（幂等，重复调用安全）：
        // 「早安」固定 9:30、「想你」在 12:00–20:00 之间随机一个时间点、「晚安」固定 22:00（1/3 概率）。
        // 用 AlarmManager 保证 App 未打开也能触发，时间/内容不固定，避免「机械准点」；
        // 设备重启后由 BootReceiver 重新设闹钟。
        GreetingScheduler.schedule(this)

        initViews()
        setupListeners()
        startAnimations()

        // 登录/启动后：从后端拉取该用户的业务数据到本地缓存，拉取完成后刷新界面展示。
        SessionManager.syncOnLogin(this) {
            refreshAffectionAndCompany()
            refreshSignInStatus()
            refreshAnniversaryStatus()
            // 拉取到的签到状态可能改变每日任务进度，同步刷新入口卡片
            refreshDailyTask()
        }
    }

    override fun onResume() {
        super.onResume()
        // 从其他页面返回时系统可能恢复了状态栏，这里重新隐藏，保持顶栏不重叠
        hideSystemStatusBar()
        // 每日首次进入主界面：发「见面礼」円（tryDailyGrant 保证每天只发一次）
        if (EconomyManager.tryDailyGrant(this, COUNTER_DAILY_OPEN, 1) == 1) {
            EconomyManager.grant(this, DAILY_OPEN_YEN)
        }
        // 「见面」已从任务清单移除：进主页不再自动标记任何任务
        refreshAffectionAndCompany()
        refreshSignInStatus()
        refreshAnniversaryStatus()
        refreshDailyTask()
        // 主页气泡互斥，优先级：签到弹窗 > 里程碑提示 > 循环指引 > 每日自言自语，
        // 避免几种提示在同一时段抢戏
        if (!maybeShowSignInDialog()) {
            if (!maybeNudgeMilestone()) {
                if (!maybeGuideLoop()) maybeShowDailySoliloquy()
            }
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
    }

    /**
     * 主界面用应用内顶栏展示时间 / 电量，因此隐藏系统状态栏，
     * 避免系统电量、网络图标与应用顶栏重叠。从顶部下滑仍可临时呼出系统栏。
     */
    private fun setupImmersiveTopBar() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemStatusBar()

        // 顶栏额外避让刘海 / 挖孔，保证隐藏状态栏后文字不会被屏幕开孔遮挡
        val topBar = findViewById<View>(R.id.top_bar)
        val basePaddingTop = topBar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { view, insets ->
            val cutoutTop = insets.getInsets(WindowInsetsCompat.Type.displayCutout()).top
            view.setPadding(
                view.paddingLeft,
                basePaddingTop + cutoutTop,
                view.paddingRight,
                view.paddingBottom
            )
            insets
        }

        // 全屏后底部功能按钮会压到手势条 / 三键导航栏上，这里按导航栏高度补足底部留白
        val bottomArea = findViewById<View>(R.id.bottom_function_area)
        val basePaddingBottom = bottomArea.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(bottomArea) { view, insets ->
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                basePaddingBottom + navBottom
            )
            insets
        }
    }

    private fun hideSystemStatusBar() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        }
    }

    /**
     * 刷新主页「每日任务」入口卡片：更新「每日任务 n/6」文案；
     * 六项全部完成且奖励尚未领取时点亮小红点，提示用户进任务页领奖。
     */
    internal fun refreshDailyTask() {
        if (!::tvDailyTaskTitle.isInitialized) return
        val (count, total) = DailyLoopManager.todayProgress(this)
        tvDailyTaskTitle.text = getString(R.string.main_daily_task_progress, count, total)
        val readyToClaim = count >= total && !DailyLoopManager.isRewardClaimed(this)
        dotDailyTaskReward.visibility = if (readyToClaim) View.VISIBLE else View.GONE
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

    override fun onPickAmbient() {
        // 打开「环境音选择」卡片面板，让用户自由选择播放哪种环境音
        showAmbientPicker()
    }

    override fun onPickLocalFile() {
        // 走系统文件选择器（SAF），由用户逐次授权具体文件，不需要申请存储/音频读取权限
        openAudioLauncher.launch(arrayOf("audio/*"))
    }

    override fun onPickStop() {
        AudioManager.stop(this)
    }

    override fun onDestroy() {
        if (::motion.isInitialized) motion.release()
        swayPollHandler?.removeCallbacks(swayPollRunnable)
        stopMusicNotes()
        super.onDestroy()
    }
}
