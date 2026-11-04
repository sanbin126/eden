package com.example.eden.feature.home


import com.example.eden.R
import com.example.eden.data.repository.ChatRepository
import com.example.eden.domain.character.MikuPersona
import com.example.eden.domain.economy.EconomyManager
import com.example.eden.domain.model.ChatSession
import com.example.eden.domain.model.GiftCatalog
import com.example.eden.domain.model.GiftKind
import com.example.eden.feature.chat.ChatActivity
import com.example.eden.feature.daily.DailyLoopManager
import com.example.eden.feature.daily.LoopStep
import com.example.eden.feature.diary.DiaryActivity
import com.example.eden.feature.game.GomokuActivity
import com.example.eden.feature.game.MinesweeperActivity
import com.example.eden.feature.game.PlatformerActivity
import com.example.eden.feature.game.XiangqiActivity
import com.example.eden.feature.music.AmbientSynth
import com.example.eden.feature.music.AudioManager
import com.example.eden.feature.music.MusicPickerDialog
import com.example.eden.feature.profile.SettingsActivity
import com.example.eden.ui.theme.edenColor
import android.Manifest
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.launch

/**
 * 左下角游戏入口：底部弹出粉色小游戏选择面板，当前有「扫雷」「五子棋」「象棋」「横版跳跃」四个。
 */
internal fun MainActivity.onGameClicked() {
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
    view.findViewById<View>(R.id.cardGamePlatformer)
        ?.setOnClickListener { openGame(sheet, PlatformerActivity::class.java) }

    sheet.show()
}

/** 关闭选择面板并进入对应小游戏。 */
private fun MainActivity.openGame(sheet: BottomSheetDialog, target: Class<*>) {
    sheet.dismiss()
    startActivity(android.content.Intent(this, target))
    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
}

/**
 * 投喂 / 送礼面板：底部弹出，按 饮料 / 食物 / 礼物 分组列出物品。
 * 点击即扣「円」并结算好感；成功时头顶气泡播放三玖的专属回应，并刷新余额 / 限次状态。
 */
internal fun MainActivity.showGiftPicker() {
    val sheet = BottomSheetDialog(this)
    val view = layoutInflater.inflate(R.layout.dialog_gift_picker, null)
    sheet.setContentView(view)
    (view.parent as? View)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    // 物品列表较长：BottomSheet 的拖拽会抢走列表的滚动手势，导致滚到底部后无法滚回上方。
    // 固定为展开态并禁用拖拽，滚动完全交给列表；点击空白处 / 返回键仍可关闭。
    sheet.behavior.skipCollapsed = true
    sheet.behavior.isDraggable = false
    sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED

    val container = view.findViewById<LinearLayout>(R.id.giftContainer)
    val tvBalance = view.findViewById<TextView>(R.id.tvGiftBalance)

    fun refreshBalance() {
        tvBalance.text = "持有：${EconomyManager.getBalance(this)} 円"
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
            if (EconomyManager.isLimitReached(this, item)) {
                subView.text = "已达限次（${item.limitLabel}）"
                row.alpha = 0.5f
            } else {
                subView.text = "好感 +${item.affection} · ${EconomyManager.remainingLabel(this, item)}"
                row.alpha = 1f
            }
        }
        refreshRow()

        row.setOnClickListener {
            when (val result = EconomyManager.feed(this, item)) {
                is EconomyManager.FeedResult.Success -> {
                    showHeadBubble(result.line)
                    // 同步写入聊天记录（「投喂了 xxx」+ 三玖的回应），与聊天页投喂保持一致
                    ChatSession.recordFeed(this, "${item.emoji} ${item.name}", result.line)
                    if (result.crossedMilestone) btnAffectionEntry.visibility = View.VISIBLE
                    refreshRow()
                    refreshBalance()
                    refreshAffectionAndCompany()
                    // 投喂完成 = 每日任务「投喂」一项达成
                    DailyLoopManager.markDone(this, LoopStep.FEED)
                    refreshDailyTask()
                }
                is EconomyManager.FeedResult.LimitReached ->
                    showToast("${item.name}${item.limitLabel}，先歇一歇吧")
                is EconomyManager.FeedResult.InsufficientYen ->
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
internal fun MainActivity.showMusicPicker() {
    // 申请通知权限（前台通知需要），失败不影响 BGM 本身
    ensureNotificationPermission()
    val dialog = MusicPickerDialog().apply {
        setListener(this@showMusicPicker)
        // 简化：先一直显示停止项，重复点也无所谓，AudioManager.stop 自身幂等
        showStopItem(true)
    }
    dialog.show(supportFragmentManager, "music_picker")
}

/**
 * 内置环境音选择面板：底部弹出卡片式界面，样式对齐小游戏选择面板。
 * 「默认」跟随天气/时间自动选择（照搬原有逻辑），其余直接播放指定氛围。
 */
internal fun MainActivity.showAmbientPicker() {
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
private fun MainActivity.startAmbientMode(sheet: BottomSheetDialog, mode: AmbientSynth.Mode?) {
    sheet.dismiss()
    if (mode == null) {
        // 走协程：AudioManager.startAmbientByWeather 是 suspend
        lifecycleScope.launch {
            AudioManager.startAmbientByWeather(this@startAmbientMode)
        }
    } else {
        AudioManager.startAmbient(this, mode)
    }
}

/**
 * 申请 POST_NOTIFICATIONS（Android 13+），失败不阻断 BGM 本身。
 * 范式参考 ChatActivity.requestNotificationPermissionIfNeeded。
 */
private fun MainActivity.ensureNotificationPermission() {
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
internal fun MainActivity.queryDisplayName(uri: Uri): String? {
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

// ==================== 原有功能保持 ====================
internal fun MainActivity.onStartChatClicked() {
    btnStartChat.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100)
        .withEndAction {
            btnStartChat.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
            val intent = android.content.Intent(this, ChatActivity::class.java)
            startActivity(intent)
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }.start()
}

internal fun MainActivity.onSettingsClicked() {
    btnSettings.animate().rotationBy(360f).setDuration(300).start()
    startActivity(android.content.Intent(this, SettingsActivity::class.java))
    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
}

internal fun MainActivity.onCharacterInfoClicked() {
    cardCharacterInfo.animate().translationZ(20f).setDuration(100)
        .withEndAction {
            cardCharacterInfo.animate().translationZ(0f).setDuration(100).start()
        }.start()

    // 首次聊天时间从 Room 异步取（与纪念日卡片同一模式），取到后再弹详情，避免主线程开 DB
    lifecycleScope.launch {
        val firstChatTime = ChatRepository(this@onCharacterInfoClicked).firstChatTime(MikuPersona.ID)
        CharacterDetailDialog(
            context = this@onCharacterInfoClicked,
            firstChatTime = firstChatTime,
            onOpenChat = {
                startActivity(android.content.Intent(this@onCharacterInfoClicked, ChatActivity::class.java))
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            },
            onOpenDiary = {
                startActivity(android.content.Intent(this@onCharacterInfoClicked, DiaryActivity::class.java))
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
        ).show()
    }
}

internal fun MainActivity.startAnimations() {
    // 呼吸等常驻变换统一交由 CharacterMotion 管理，避免与瞬时动作抢属性
    motion.start()
}
