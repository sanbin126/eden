package com.example.eden.feature.home


import com.example.eden.R
import com.example.eden.domain.character.MikuPersona
import com.example.eden.domain.economy.EconomyManager
import com.example.eden.feature.daily.DailyLoopManager
import com.example.eden.feature.daily.LoopStep
import com.example.eden.feature.story.MilestoneRepository
import com.example.eden.ui.theme.edenColor
import android.animation.ValueAnimator
import android.graphics.PorterDuff
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// ==================== 触摸处理 ====================
internal fun MainActivity.handleCharacterTouch(view: View, x: Float, y: Float) {
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
        ratioY >= MainActivity.EARPHONE_TOP && ratioY <= MainActivity.EARPHONE_BOTTOM &&
                (ratioX <= MainActivity.EARPHONE_LEFT_MAX || ratioX >= MainActivity.EARPHONE_RIGHT_MIN) -> {
            motion.tiltHead()
            MikuPersona.EARPHONE_LINES.random()
        }
        // 头部：开心弹跳
        ratioY >= MainActivity.HEAD_TOP && ratioY <= MainActivity.HEAD_BOTTOM &&
                ratioX >= MainActivity.HEAD_LEFT && ratioX <= MainActivity.HEAD_RIGHT -> {
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
        ratioY >= MainActivity.CHEST_TOP && ratioY <= MainActivity.CHEST_BOTTOM &&
                ratioX >= MainActivity.CHEST_LEFT && ratioX <= MainActivity.CHEST_RIGHT -> {
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
private fun MainActivity.onTouchedLoop() {
    if (!DailyLoopManager.markDone(this, LoopStep.TOUCH)) return
    refreshDailyTask()
    if (EconomyManager.tryDailyGrant(this, MainActivity.COUNTER_TOUCH_AFFECTION, 1) == 1) {
        val (newValue, crossed) = EconomyManager.increase(this, 1)
        if (crossed) MilestoneRepository.onCrossed(this, newValue)
        refreshAffectionAndCompany()
    }
}

/**
 * 触碰角色产出「円」：带 3 秒冷却 + 每日上限，避免连点刷币。
 * 冷却中静默跳过；今日已领满时用一句指向聊天的台词把用户引向下一个动作。
 */
private fun MainActivity.earnTouchYen() {
    val now = System.currentTimeMillis()
    if (now - lastTouchEarnAt < MainActivity.TOUCH_EARN_COOLDOWN_MS) return
    lastTouchEarnAt = now
    val added = EconomyManager.earn(
        this,
        MainActivity.COUNTER_TOUCH_YEN,
        MainActivity.TOUCH_YEN,
        MainActivity.TOUCH_YEN_DAILY_CAP
    )
    if (added > 0) {
        refreshAffectionAndCompany()
        return
    }
    // 今天摸够了：出口台词每天只提一次，把「摸头」这一步引向聊天
    if (EconomyManager.dailyValue(this, MainActivity.COUNTER_TOUCH_YEN) >= MainActivity.TOUCH_YEN_DAILY_CAP &&
        EconomyManager.tryDailyGrant(this, MainActivity.COUNTER_TOUCH_EXIT, 1) == 1
    ) {
        ivCharacter.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            val line = MikuPersona.LOOP_GUIDE_LINES[LoopStep.CHAT.id]?.random() ?: return@postDelayed
            showHeadBubble(line)
        }, 900L)
    }
}

private fun MainActivity.showBubble(rawX: Float, rawY: Float, text: String) {
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
    var top = touchYInParent + dpToPx(MainActivity.BUBBLE_OFFSET_Y_DP)

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
    }, MainActivity.BUBBLE_DURATION)
}

private fun MainActivity.spawnHearts(view: View, touchX: Float, touchY: Float) {
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

internal fun MainActivity.dpToPx(dp: Int): Int {
    return (dp * resources.displayMetrics.density).toInt()
}

// ==================== 音乐氛围（音符跳动） ====================

/**
 * 根据音乐播放状态刷新氛围：开始播放时冒音符；停止播放时收掉音符。
 * 由每秒一次的 swayPollRunnable 驱动。
 */
internal fun MainActivity.updateMusicMood(playing: Boolean) {
    if (playing) {
        if (!wasMusicPlaying) {
            startMusicNotes()
        }
    } else if (wasMusicPlaying) {
        stopMusicNotes()
    }
    wasMusicPlaying = playing
}

private fun MainActivity.startMusicNotes() {
    if (noteEmitHandler != null) return
    noteEmitHandler = Handler(Looper.getMainLooper())
    noteEmitHandler?.post(noteEmitRunnable)
}

internal fun MainActivity.stopMusicNotes() {
    noteEmitHandler?.removeCallbacks(noteEmitRunnable)
    noteEmitHandler = null
}

/** 在立绘周围冒出一两个音符，向上飘散并淡出，像跟着音乐轻轻哼唱 */
internal fun MainActivity.spawnMusicNotes() {
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

/**
 * 在角色头顶显示一句气泡，样式与触摸气泡一致。
 * 复用 [MainActivity.currentBubble] 引用：新气泡会顶替旧的，逐句顶替形成「自言自语」节奏。
 */
internal fun MainActivity.showHeadBubble(text: String) {
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
    }, MainActivity.BUBBLE_DURATION)
}

internal fun MainActivity.showToast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
