package com.example.eden

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 音乐播放前台 Service。
 *
 * 维护两类播放源：
 * - **内置环境音**：优先用 [MediaPlayer] 播放 `res/raw/bgm_*.mp3`（如果用户用 Suno 生成丢进来）；
 *   资源缺失时降级用 [AmbientSynth] 实时合成。
 * - **本地音乐文件**：用户自选的 `content://` uri，用 [MediaPlayer] 播放。
 *
 * 同时维护前台通知 + 3 个控制按钮（上一首 / 播放-暂停 / 下一首）。
 * 由 [MusicManager] 通过 Intent 命令驱动状态切换。
 *
 * Android 14+ (API 34+) 前台 Service 必须声明 foregroundServiceType=mediaPlayback，
 * 启动时用 [ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK]。
 */
class MusicService : Service() {

    companion object {
        const val CHANNEL_ID = "eden_music"
        const val NOTIF_ID = 2001
        private const val TAG = "MusicService"

        /**
         * 当前是否正在播放。静态暴露，供 UI 轮询（MainActivity 的立绘随乐摇摆每秒读一次），
         * 因为 UI 拿不到 Service 实例。Service 与 UI 同进程，且同一时刻只有一个实例。
         */
        @Volatile
        var isPlayingNow: Boolean = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var ambientSynth: AmbientSynth? = null
    private var mediaPlayer: MediaPlayer? = null

    private enum class SourceKind { AMBIENT_SYNTH, AMBIENT_MP3, LOCAL_FILE, NONE }
    private var sourceKind: SourceKind = SourceKind.NONE
    private var currentMode: AmbientSynth.Mode = AmbientSynth.Mode.DAY
    private var currentTrackName: String = "Eden 背景音乐"
    private var currentLocalUri: Uri? = null

    // ============== Service 生命周期 ==============

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand: action=$action")

        try {
            // 首次启动 / 任何 action 来时都先确保前台
            startForegroundIfNeeded()
            when (action) {
                MusicManager.ACTION_START_AMBIENT -> {
                    val modeName = intent?.getStringExtra(MusicManager.EXTRA_AMBIENT_MODE)
                        ?: AmbientSynth.Mode.DAY.name
                    val mode = runCatching { AmbientSynth.Mode.valueOf(modeName) }
                        .getOrDefault(AmbientSynth.Mode.DAY)
                    val trackName = intent?.getStringExtra(MusicManager.EXTRA_TRACK_NAME)
                        ?: MusicManager.modeName(mode)
                    handleStartAmbient(mode, trackName)
                }
                MusicManager.ACTION_START_LOCAL -> {
                    val uriStr = intent?.getStringExtra(MusicManager.EXTRA_LOCAL_URI)
                    val name = intent?.getStringExtra(MusicManager.EXTRA_TRACK_NAME) ?: "本地音乐"
                    if (uriStr != null) handleStartLocal(Uri.parse(uriStr), name)
                }
                MusicManager.ACTION_PAUSE -> handlePause()
                MusicManager.ACTION_RESUME -> handleResume()
                MusicManager.ACTION_STOP -> handleStop()
                MusicManager.ACTION_NEXT -> handleNext()
                MusicManager.ACTION_PREV -> handlePrev()
                else -> Log.w(TAG, "unknown action: $action")
            }
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand error for action=$action", e)
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseAll()
        scope.cancel()
        super.onDestroy()
    }

    // ============== 命令处理 ==============

    private fun handleStartAmbient(mode: AmbientSynth.Mode, trackName: String) {
        releaseAll()
        currentMode = mode
        currentTrackName = trackName
        currentLocalUri = null

        // 1. 检查 res/raw 是否有对应 mp3（用户用 Suno 替换）
        val rawId = MusicManager.rawResIdForMode(this, mode)
        if (rawId != 0) {
            // 用 MediaPlayer 播放 res/raw mp3 循环
            try {
                val mp = MediaPlayer.create(this, rawId)?.apply {
                    isLooping = true
                    setVolume(0.5f, 0.5f)
                    start()
                } ?: run {
                    Log.w(TAG, "MediaPlayer.create returned null for rawId=$rawId, fallback to synth")
                    startSynth(mode)
                    return
                }
                mediaPlayer = mp
                sourceKind = SourceKind.AMBIENT_MP3
                isPlayingNow = true
                Log.d(TAG, "ambient mp3 playing: rawId=$rawId")
            } catch (e: Exception) {
                Log.e(TAG, "mp3 play failed, fallback to synth", e)
                startSynth(mode)
            }
        } else {
            // 2. 没有 mp3 → 用 AmbientSynth 实时合成
            startSynth(mode)
        }
    }

    private fun startSynth(mode: AmbientSynth.Mode) {
        val synth = AmbientSynth().also { ambientSynth = it }
        synth.start(mode)
        sourceKind = SourceKind.AMBIENT_SYNTH
        isPlayingNow = true
    }

    private fun handleStartLocal(uri: Uri, name: String) {
        releaseAll()
        try {
            val mp = MediaPlayer().apply {
                setDataSource(this@MusicService, uri)
                isLooping = true
                setVolume(0.7f, 0.7f)
                prepare()
                start()
            }
            mediaPlayer = mp
            sourceKind = SourceKind.LOCAL_FILE
            currentLocalUri = uri
            currentTrackName = name
            isPlayingNow = true
            // 取持久权限，避免 Service 重启后 uri 失效
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "takePersistableUriPermission failed", e)
            }
            Log.d(TAG, "local file playing: $uri")
        } catch (e: Exception) {
            Log.e(TAG, "start local file failed", e)
            // 失败兜底：切到 ambient
            scope.launch {
                // 复用 MusicManager 走天气分支
                MusicManager.startAmbientByWeather(this@MusicService)
            }
        }
    }

    private fun handlePause() {
        when (sourceKind) {
            SourceKind.AMBIENT_SYNTH -> ambientSynth?.pause()
            SourceKind.AMBIENT_MP3, SourceKind.LOCAL_FILE -> try { mediaPlayer?.pause() } catch (e: Exception) { Log.w(TAG, "pause mp fail", e) }
            SourceKind.NONE -> {}
        }
        isPlayingNow = false
    }

    private fun handleResume() {
        when (sourceKind) {
            SourceKind.AMBIENT_SYNTH -> ambientSynth?.resume()
            SourceKind.AMBIENT_MP3, SourceKind.LOCAL_FILE -> try { mediaPlayer?.start() } catch (e: Exception) { Log.w(TAG, "resume mp fail", e) }
            SourceKind.NONE -> {}
        }
        isPlayingNow = true
    }

    private fun handleStop() {
        releaseAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * 上一首 / 下一首：仅 ambient 模式下在 4 种天气模式间循环切换（演示用）。
     * 本地文件模式下 no-op（不支持列表）。
     */
    private fun handleNext() {
        if (sourceKind == SourceKind.AMBIENT_SYNTH || sourceKind == SourceKind.AMBIENT_MP3) {
            val next = when (currentMode) {
                AmbientSynth.Mode.DAY -> AmbientSynth.Mode.RAIN
                AmbientSynth.Mode.RAIN -> AmbientSynth.Mode.SNOW
                AmbientSynth.Mode.SNOW -> AmbientSynth.Mode.NIGHT
                AmbientSynth.Mode.NIGHT -> AmbientSynth.Mode.DAY
            }
            handleStartAmbient(next, MusicManager.modeName(next))
        }
    }

    private fun handlePrev() {
        if (sourceKind == SourceKind.AMBIENT_SYNTH || sourceKind == SourceKind.AMBIENT_MP3) {
            val prev = when (currentMode) {
                AmbientSynth.Mode.DAY -> AmbientSynth.Mode.NIGHT
                AmbientSynth.Mode.RAIN -> AmbientSynth.Mode.DAY
                AmbientSynth.Mode.SNOW -> AmbientSynth.Mode.RAIN
                AmbientSynth.Mode.NIGHT -> AmbientSynth.Mode.SNOW
            }
            handleStartAmbient(prev, MusicManager.modeName(prev))
        }
    }

    // ============== 通知 ==============

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Eden 背景音乐",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Eden 内置 BGM 与本地音乐播放控制"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundIfNeeded() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ 必须指定 foregroundServiceType
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0),
        )

        val prevPi = commandPi(MusicManager.ACTION_PREV, 1)
        val playPausePi = commandPi(
            if (isPlayingNow) MusicManager.ACTION_PAUSE else MusicManager.ACTION_RESUME,
            2,
        )
        val nextPi = commandPi(MusicManager.ACTION_NEXT, 3)

        val playPauseIcon =
            if (isPlayingNow) R.drawable.ic_pause else R.drawable.ic_play
        val playPauseText = if (isPlayingNow) "暂停" else "播放"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music)
            .setContentTitle("Eden · 陪伴中")
            .setContentText(currentTrackName)
            .setContentIntent(contentPi)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_skip_prev, "上一首", prevPi)
            .addAction(playPauseIcon, playPauseText, playPausePi)
            .addAction(R.drawable.ic_skip_next, "下一首", nextPi)
            .build()
    }

    private fun commandPi(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, MusicService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0),
        )
    }

    // ============== 资源清理 ==============

    private fun releaseAll() {
        ambientSynth?.stop()
        ambientSynth = null
        mediaPlayer?.let {
            try { it.stop() } catch (e: Exception) { Log.w(TAG, "mp.stop fail", e) }
            try { it.release() } catch (e: Exception) { Log.w(TAG, "mp.release fail", e) }
        }
        mediaPlayer = null
        sourceKind = SourceKind.NONE
        isPlayingNow = false
    }
}
