package com.example.eden

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import java.text.SimpleDateFormat
import java.util.*
import android.graphics.PorterDuff
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

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
    }

    // ==================== 台词池 ====================
    private val headLines = listOf(
        "唔…别摸头…会变矮的…",
        "（缩了缩脖子）痒……",
        "头发…被你弄乱了啦…",
        "……摸头的话，我会害羞的。"
    )

    private val earphoneLines = listOf(
        "（把耳机音量调小）嗯？你说什么？",
        "这个耳机…是很重要的东西…",
        "……你要听吗？（递出一只耳机）"
    )

    private val chestLines = listOf(
        "……！！（捂住胸口后退一步）你、你干什么！",
        "（脸瞬间通红）变态…",
        "再碰这里我就不理你了…",
        "（背过身去，耳根发红）……"
    )

    private val otherLines = listOf(
        "嗯？怎么了？",
        "有什么话想说吗？",
        "（歪头看你）想聊天的话，点下面的按钮就好…",
        "……（疑惑地看着你）"
    )

    // ==================== 视图组件 ====================
    private lateinit var ivCharacter: ImageView
    private lateinit var characterContainer: FrameLayout
    private lateinit var btnStartChat: Button
    private lateinit var btnSettings: ImageView
    private lateinit var cardCharacterInfo: CardView
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvAffinity: TextView
    private lateinit var tvOnlineTime: TextView
    private lateinit var btnCalendar: ImageView
    private lateinit var btnDiary: ImageView
    private lateinit var btnTimer: ImageView
    private lateinit var btnMusic: ImageView
    private lateinit var cardSignIn: CardView
    private lateinit var tvSignInText: TextView
    private lateinit var btnSignInEntry: LinearLayout

    private var breathAnimator: ValueAnimator? = null
    private var breathAnimatorY: ValueAnimator? = null
    private var timeUpdateHandler: Handler? = null
    private var currentBubble: TextView? = null
    private val heartViews = mutableListOf<ImageView>()
    private lateinit var heartParticles: HeartParticleView

    private val colors = listOf(
        0xFFFF69B4.toInt(),
        0xFFFF1493.toInt(),
        0xFFFFB6C1.toInt(),
        0xFFFF85A2.toInt(),
        0xFFFF4081.toInt()
    )

    private val timeUpdateRunnable = object : Runnable {
        override fun run() {
            updateCurrentTime()
            timeUpdateHandler?.postDelayed(this, 60000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        startAnimations()
        startTimeUpdates()
    }

    override fun onResume() {
        super.onResume()
        refreshAffectionAndCompany()
        refreshSignInStatus()
        checkAndShowDailySignDialog()
    }

    private fun initViews() {
        ivCharacter = findViewById(R.id.iv_character)
        characterContainer = findViewById(R.id.character_container)
        heartParticles = findViewById(R.id.heart_particles)
        btnStartChat = findViewById(R.id.btn_start_chat)
        btnSettings = findViewById(R.id.btn_settings)
        cardCharacterInfo = findViewById(R.id.card_character_info)
        tvCurrentTime = findViewById(R.id.tv_current_time)
        tvAffinity = findViewById(R.id.tv_affinity)
        tvOnlineTime = findViewById(R.id.tv_online_time)

        btnCalendar = findViewById(R.id.btn_calendar)
        btnDiary = findViewById(R.id.btn_diary)
        btnTimer = findViewById(R.id.btn_timer)
        btnMusic = findViewById(R.id.btn_music)
        cardSignIn = findViewById(R.id.card_sign_in)
        tvSignInText = findViewById(R.id.tv_sign_in_text)
        btnSignInEntry = findViewById(R.id.btn_sign_in_entry)

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

        btnCalendar.setOnClickListener { showToast("日历功能开发中") }
        btnDiary.setOnClickListener { showToast("日志功能开发中") }
        btnTimer.setOnClickListener { showToast("计时器功能开发中") }
        btnMusic.setOnClickListener { showToast("音乐功能开发中") }
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
            // 耳机（挂在脖子上，下巴下方 ~ 胸口上方）
            ratioY >= EARPHONE_TOP && ratioY <= EARPHONE_BOTTOM &&
                    (ratioX <= EARPHONE_LEFT_MAX || ratioX >= EARPHONE_RIGHT_MIN) -> {
                earphoneLines.random()
            }
            // 头部
            ratioY >= HEAD_TOP && ratioY <= HEAD_BOTTOM &&
                    ratioX >= HEAD_LEFT && ratioX <= HEAD_RIGHT -> {
                // 确保 view 宽高已测量，避免坐标 0 导致爱心从左上角飞出
                if (view.width > 0 && view.height > 0) {
                    spawnHearts(view, x, y)
                } else {
                    // 若未测量，延迟到下一帧再触发（极少发生）
                    view.post { spawnHearts(view, x, y) }
                }
                headLines.random()
            }
            // 胸部（下移，原耳机位置下方）
            ratioY >= CHEST_TOP && ratioY <= CHEST_BOTTOM &&
                    ratioX >= CHEST_LEFT && ratioX <= CHEST_RIGHT -> {
                chestLines.random()
            }
            // 其他
            else -> otherLines.random()
        }

        showBubble(x, y, line)
    }

    private fun showBubble(rawX: Float, rawY: Float, text: String) {
        val bubble = TextView(this).apply {
            this.text = text
            setTextColor(0xFF333333.toInt())
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
        val location = IntArray(2)
        ivCharacter.getLocationInWindow(location)
        val touchXInParent = location[0] + rawX
        val touchYInParent = location[1] + rawY

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
        showToast("设置功能开发中")
    }

    private fun onCharacterInfoClicked() {
        cardCharacterInfo.animate().translationZ(20f).setDuration(100)
            .withEndAction {
                cardCharacterInfo.animate().translationZ(0f).setDuration(100).start()
            }.start()
        showToast("查看角色详情")
    }

    private fun startAnimations() {
        breathAnimator = ObjectAnimator.ofFloat(ivCharacter, "scaleX", 1f, 1.02f, 1f).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
        breathAnimatorY = ObjectAnimator.ofFloat(ivCharacter, "scaleY", 1f, 1.02f, 1f).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
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
        tvAffinity.text = "$affection/100"

        val prefs = getSharedPreferences("company_prefs", Context.MODE_PRIVATE)
        val lastDate = prefs.getString("last_date", "")
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val minutes = if (lastDate == today) {
            prefs.getInt("today_minutes", 0)
        } else {
            prefs.edit().putString("last_date", today).putInt("today_minutes", 0).apply()
            0
        }
        tvOnlineTime.text = "今日陪伴：${formatDuration(minutes)}"
    }

    private fun formatDuration(totalMinutes: Int): String {
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) {
            "${hours}小时${minutes}分钟"
        } else {
            "${minutes}分钟"
        }
    }

    private fun refreshSignInStatus() {
        if (SignInManager.isTodaySigned(this)) {
            tvSignInText.text = "已签到"
            tvSignInText.setTextColor(0xFF888888.toInt())
        } else {
            tvSignInText.text = "签到"
            tvSignInText.setTextColor(0xFFFF4081.toInt())
        }
    }

    private fun checkAndShowDailySignDialog() {
        val prefs = getSharedPreferences("daily_sign_shown", MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val lastShownDate = prefs.getString("shown_date", "")

        if (lastShownDate != today && !SignInManager.isTodaySigned(this)) {
            showDailySignDialog()
            prefs.edit().putString("shown_date", today).apply()
        }
    }

    private fun showDailySignDialog() {
        val continuousDays = SignInManager.getContinuousDays(this)
        val builder = AlertDialog.Builder(this)
        builder.setTitle("每日签到")
        builder.setMessage("你已连续签到 ${continuousDays} 天\n\n今天还没签到哦，要签到吗？")
        builder.setPositiveButton("签到") { dialog, _ ->
            val newDays = SignInManager.signToday(this)
            Toast.makeText(this, "签到成功！连续签到 ${newDays} 天", Toast.LENGTH_SHORT).show()
            refreshSignInStatus()
            dialog.dismiss()
        }
        builder.setNegativeButton("取消") { dialog, _ ->
            dialog.dismiss()
        }
        builder.setCancelable(false)
        builder.show()
    }

    private fun showSignCalendarDialog() {
        val dialog = SignCalendarDialog(this)
        dialog.show()
        dialog.setOnDismissListener {
            refreshSignInStatus()
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        breathAnimator?.cancel()
        breathAnimatorY?.cancel()
        timeUpdateHandler?.removeCallbacks(timeUpdateRunnable)
        super.onDestroy()
    }
}