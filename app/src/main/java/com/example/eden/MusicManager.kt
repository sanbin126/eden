package com.example.eden

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 音乐功能统一入口。
 *
 * 所有调用方（MainActivity / MusicPickerDialog / 通知 PendingIntent）只跟这个 object 打交道，
 * 不直接操作 [MusicService]，保证命令收口在一处。
 *
 * 支持两类播放源：
 * 1. **内置环境音**（[startAmbientByWeather]）：按当前天气 + 系统时间映射到 [AmbientSynth.Mode]，
 *    交给 MusicService。若 `res/raw/` 下存在对应 mp3（如 `bgm_day.mp3`），MusicService 优先用
 *    MediaPlayer 播放，AmbientSynth 不会启动。
 * 2. **本地音乐文件**（[startLocalFile]）：用户自选的本地音频文件，交给 MusicService 用
 *    MediaPlayer 播放。版权责任在用户。
 *
 * 命令传输：通过 Intent + ContextCompat.startForegroundService / startService，
 * Service 收到后切换播放源 / 状态。
 */
object MusicManager {

    private const val TAG = "MusicManager"

    // Intent actions
    const val ACTION_START_AMBIENT = "com.example.eden.music.START_AMBIENT"
    const val ACTION_START_LOCAL = "com.example.eden.music.START_LOCAL"
    const val ACTION_PAUSE = "com.example.eden.music.PAUSE"
    const val ACTION_RESUME = "com.example.eden.music.RESUME"
    const val ACTION_STOP = "com.example.eden.music.STOP"
    const val ACTION_NEXT = "com.example.eden.music.NEXT"
    const val ACTION_PREV = "com.example.eden.music.PREV"

    // Intent extras
    const val EXTRA_AMBIENT_MODE = "ambient_mode"
    const val EXTRA_LOCAL_URI = "local_uri"
    const val EXTRA_TRACK_NAME = "track_name"

    /**
     * 按当前天气启动内置环境音。
     *
     * 内部调用 [WeatherManager.getWeather]（有 1 小时缓存，每次启动重读一次可接受）。
     * 必须 suspend，调用方需在协程里调（如 Activity 的 lifecycleScope.launch）。
     */
    suspend fun startAmbientByWeather(context: Context) {
        val mode = resolveMode(context)
        startAmbient(context, mode)
    }

    /**
     * 按指定氛围模式启动内置环境音（用户手动选择时使用）。
     *
     * 与 [startAmbientByWeather] 的区别：不走天气/时间自动解析，直接播放用户选定的模式。
     */
    fun startAmbient(context: Context, mode: AmbientSynth.Mode) {
        val name = modeName(mode)
        Log.d(TAG, "startAmbient: mode=$mode, name=$name")

        val intent = Intent(context, MusicService::class.java).apply {
            action = ACTION_START_AMBIENT
            putExtra(EXTRA_AMBIENT_MODE, mode.name)
            putExtra(EXTRA_TRACK_NAME, name)
        }
        startServiceSafely(context, intent)
    }

    /**
     * 启动本地音乐文件播放。
     *
     * @param uri 通过 SAF（ActivityResultContracts.OpenDocument）拿到的 content:// uri
     * @param displayName 通知栏显示的曲目名（一般是文件名）
     */
    fun startLocalFile(context: Context, uri: Uri, displayName: String) {
        Log.d(TAG, "startLocalFile: uri=$uri, name=$displayName")
        val intent = Intent(context, MusicService::class.java).apply {
            action = ACTION_START_LOCAL
            putExtra(EXTRA_LOCAL_URI, uri.toString())
            putExtra(EXTRA_TRACK_NAME, displayName)
        }
        startServiceSafely(context, intent)
    }

    fun pause(context: Context) =
        sendCommand(context, ACTION_PAUSE)

    fun resume(context: Context) =
        sendCommand(context, ACTION_RESUME)

    fun stop(context: Context) =
        sendCommand(context, ACTION_STOP)

    fun next(context: Context) =
        sendCommand(context, ACTION_NEXT)

    fun prev(context: Context) =
        sendCommand(context, ACTION_PREV)

    // ============== 内部 ==============

    private fun sendCommand(context: Context, action: String) {
        val intent = Intent(context, MusicService::class.java).apply { this.action = action }
        startServiceSafely(context, intent)
    }

    private fun startServiceSafely(context: Context, intent: Intent) {
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            Log.e(TAG, "startService failed", e)
        }
    }

    /**
     * 解析当前氛围模式：天气 → ambient mode；22:00~06:00 强制 NIGHT。
     */
    private suspend fun resolveMode(context: Context): AmbientSynth.Mode =
        withContext(Dispatchers.IO) {
            // 夜晚优先（即便天气是晴，夜里也走夜氛围）
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            if (hour in 22..23 || hour in 0..5) {
                return@withContext AmbientSynth.Mode.NIGHT
            }

            val weather = runCatching { WeatherManager.getWeather(context) }
                .getOrNull()
            val text = weather?.text.orEmpty()
            when {
                text.contains("雨") || text.contains("雷") -> AmbientSynth.Mode.RAIN
                text.contains("雪") -> AmbientSynth.Mode.SNOW
                else -> AmbientSynth.Mode.DAY
            }
        }

    /**
     * 通知栏内容显示用：模式 → 中文名。
     */
    fun modeName(mode: AmbientSynth.Mode): String = when (mode) {
        AmbientSynth.Mode.DAY -> "晴天环境音 · 内置 BGM"
        AmbientSynth.Mode.RAIN -> "雨天环境音 · 内置 BGM"
        AmbientSynth.Mode.SNOW -> "雪天环境音 · 内置 BGM"
        AmbientSynth.Mode.NIGHT -> "夜晚环境音 · 内置 BGM"
    }

    /**
     * 检查 res/raw 下是否存在对应天气的 mp3（如 `bgm_day.mp3`）。
     * 命名规则：bgm_day / bgm_rain / bgm_snow / bgm_night。
     * 由 [MusicService] 在启动播放前调用，决定走 MediaPlayer 还是 AmbientSynth。
     */
    fun rawResIdForMode(context: Context, mode: AmbientSynth.Mode): Int {
        val name = "bgm_" + mode.name.lowercase()
        return context.resources.getIdentifier(name, "raw", context.packageName)
    }
}
