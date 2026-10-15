package com.example.eden

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 实时合成环境氛围音（0 版权、无需任何外部资源）。
 *
 * 用 [AudioTrack] MODE_STREAM 持续推送 16bit PCM 数据，由 Kotlin 协程在 IO 调度器上跑合成循环。
 *
 * 4 种氛围模式由 [MusicManager] 根据天气 + 系统时间选择：
 * - [Mode.DAY]   晴/多云/阴/雾  → 低频基底 + 偶发鸟鸣扫频
 * - [Mode.RAIN]  雨/雷雨        → 白噪声 + 低通滤波（雷雨再加 80Hz 衰减包络）
 * - [Mode.SNOW]  雪             → 白噪声 + 高通滤波 + 偶发哨声
 * - [Mode.NIGHT] 22:00~06:00    → 100Hz 嗡基底 + 8000Hz 虫鸣脉冲
 *
 * 全局音量约 -20dB（陪伴不打扰）。
 *
 * 若用户后续用 Suno 生成 `bgm_day.mp3` 等丢进 `res/raw/`，由 [MusicService] 优先用 MediaPlayer 播放，
 * AmbientSynth 不会被启动（详见方案 .trae/documents/music-bgm-feature.md）。
 */
class AmbientSynth {

    enum class Mode { DAY, RAIN, SNOW, NIGHT }

    private val sampleRate = 22050
    private val bufferSize = AudioTrack.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    ).coerceAtLeast(2048)

    private var audioTrack: AudioTrack? = null
    private var synthJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile private var currentMode: Mode = Mode.DAY
    @Volatile private var isPlaying = false

    /**
     * 启动合成。重复调用安全：先停旧实例再起新模式。
     */
    fun start(mode: Mode) {
        stopInternal()
        currentMode = mode
        val track = createTrack().also { audioTrack = it }
        track.play()
        isPlaying = true
        synthJob = scope.launch { synthLoop(mode, track) }
        Log.d(TAG, "start: mode=$mode, bufferSize=$bufferSize")
    }

    fun pause() {
        if (!isPlaying) return
        try { audioTrack?.pause() } catch (e: IllegalStateException) { Log.w(TAG, "pause fail", e) }
        isPlaying = false
    }

    fun resume() {
        if (isPlaying) return
        try {
            audioTrack?.play()
            isPlaying = true
        } catch (e: IllegalStateException) { Log.w(TAG, "resume fail", e) }
    }

    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        synthJob?.cancel()
        synthJob = null
        audioTrack?.let {
            try {
                if (isPlaying) it.stop()
                it.release()
            } catch (e: IllegalStateException) { Log.w(TAG, "release fail", e) }
        }
        audioTrack = null
        isPlaying = false
    }

    private fun createTrack(): AudioTrack {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        return AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /**
     * 合成主循环：每轮生成 [CHUNK_SIZE] 个样本，推给 AudioTrack（阻塞写）。
     * 协程取消时 ensureActive 抛 CancellationException 退出。
     */
    private suspend fun synthLoop(mode: Mode, track: AudioTrack) {
        val chunk = 1024
        val state = SynthState(mode)
        val noiseState = NoiseState()
        while (scope.isActive) {
            // 协程被外部 cancel 时跳出循环（scope.isActive 只反映外层 scope，不反映本 launch）
            try { coroutineContext.ensureActive() } catch (e: Exception) { break }
            val samples = synthChunk(mode, state, noiseState, chunk)
            // 写入是阻塞调用（写满缓冲区会等），IO 调度器不会卡 UI
            track.write(samples, 0, chunk, AudioTrack.WRITE_BLOCKING)
        }
    }

    private fun synthChunk(mode: Mode, s: SynthState, n: NoiseState, count: Int): ShortArray {
        val out = ShortArray(count)
        when (mode) {
            Mode.DAY -> synthDay(s, n, out)
            Mode.RAIN -> synthRain(s, n, out)
            Mode.SNOW -> synthSnow(s, n, out)
            Mode.NIGHT -> synthNight(s, n, out)
        }
        return out
    }

    // ============== 各模式合成实现 ==============

    /**
     * 晴天：200Hz 柔和正弦基底 + 偶发鸟鸣扫频（2000~4000Hz，3~8s 随机间隔）。
     */
    private fun synthDay(s: SynthState, n: NoiseState, out: ShortArray) {
        val baseFreq = 200.0
        val baseAmp = 0.06   // 基底音量低
        for (i in out.indices) {
            val t = s.timeBase++
            var sample = sin(2 * PI * baseFreq * t / sampleRate) * baseAmp

            // 鸟鸣事件触发
            if (t - s.lastBirdAt > s.nextBirdGap) {
                s.birdEnv = 1.0
                s.birdStartFreq = 2000 + Random.nextDouble(0.0, 1000.0)
                s.birdEndFreq = s.birdStartFreq + 1000 + Random.nextDouble(0.0, 1000.0)
                s.lastBirdAt = t
                s.nextBirdGap = (sampleRate * (3 + Random.nextDouble(0.0, 5.0))).toLong()
                s.birdDur = (sampleRate * (0.15 + Random.nextDouble(0.0, 0.25))).toLong()
                s.birdElapsed = 0L
            }
            if (s.birdEnv > 0.0) {
                val progress = (s.birdElapsed.toDouble() / s.birdDur).coerceAtMost(1.0)
                val freq = s.birdStartFreq + (s.birdEndFreq - s.birdStartFreq) * progress
                val env = (1.0 - progress) * 0.15 * (1.0 - progress)  // 起伏包络
                sample += sin(2 * PI * freq * t / sampleRate) * env
                s.birdElapsed++
                if (progress >= 1.0) s.birdEnv = 0.0
            }
            out[i] = clamp(sample)
        }
    }

    /**
     * 雨天：白噪声 + 低通滤波（一阶 IIR）模拟雨声"沙沙"；
     * 雷雨时每隔 30~60s 加一个 80Hz 衰减"轰"包络。
     */
    private fun synthRain(s: SynthState, n: NoiseState, out: ShortArray) {
        val lpAlpha = 0.12  // 低通系数（越小越暗）
        val rainAmp = 0.18
        for (i in out.indices) {
            val t = s.timeBase++
            val white = n.nextWhite()
            // 一阶低通：y = y*(1-a) + x*a
            s.lpState = s.lpState * (1 - lpAlpha) + white * lpAlpha
            var sample = s.lpState * rainAmp

            // 雷鸣包络
            if (t - s.lastThunderAt > s.nextThunderGap) {
                s.thunderEnv = 1.0
                s.lastThunderAt = t
                s.nextThunderGap = (sampleRate * (30 + Random.nextDouble(0.0, 30.0))).toLong()
                s.thunderElapsed = 0L
                s.thunderDur = (sampleRate * (1.2 + Random.nextDouble(0.0, 0.6))).toLong()
            }
            if (s.thunderEnv > 0.0) {
                val progress = (s.thunderElapsed.toDouble() / s.thunderDur).coerceAtMost(1.0)
                val env = (1.0 - progress) * (1.0 - progress) * 0.6  // 衰减
                sample += sin(2 * PI * 80.0 * t / sampleRate) * env
                s.thunderElapsed++
                if (progress >= 1.0) s.thunderEnv = 0.0
            }
            out[i] = clamp(sample)
        }
    }

    /**
     * 雪天：白噪声 + 高通滤波（风声）+ 偶发高频"哨"扫频。
     */
    private fun synthSnow(s: SynthState, n: NoiseState, out: ShortArray) {
        val hpAlpha = 0.95
        val snowAmp = 0.15
        for (i in out.indices) {
            val t = s.timeBase++
            val white = n.nextWhite()
            // 一阶高通：y = a*(y_prev + x - x_prev)
            val hp = hpAlpha * (s.hpState + white - s.hpPrevX)
            s.hpPrevX = white
            s.hpState = hp
            var sample = hp * snowAmp

            // 哨声事件
            if (t - s.lastWhistleAt > s.nextWhistleGap) {
                s.whistleEnv = 1.0
                s.lastWhistleAt = t
                s.nextWhistleGap = (sampleRate * (6 + Random.nextDouble(0.0, 10.0))).toLong()
                s.whistleDur = (sampleRate * (0.8 + Random.nextDouble(0.0, 0.6))).toLong()
                s.whistleElapsed = 0L
                s.whistleFreq = 3000 + Random.nextDouble(0.0, 2000.0)
            }
            if (s.whistleEnv > 0.0) {
                val progress = (s.whistleElapsed.toDouble() / s.whistleDur).coerceAtMost(1.0)
                val env = sin(PI * progress) * 0.05
                sample += sin(2 * PI * s.whistleFreq * t / sampleRate) * env
                s.whistleElapsed++
                if (progress >= 1.0) s.whistleEnv = 0.0
            }
            out[i] = clamp(sample)
        }
    }

    /**
     * 夜晚：100Hz 嗡基底 + 8000Hz 间歇虫鸣脉冲（2~5s 间隔）。
     */
    private fun synthNight(s: SynthState, n: NoiseState, out: ShortArray) {
        val baseFreq = 100.0
        val baseAmp = 0.04
        for (i in out.indices) {
            val t = s.timeBase++
            var sample = sin(2 * PI * baseFreq * t / sampleRate) * baseAmp

            // 虫鸣脉冲
            if (t - s.lastCricketAt > s.nextCricketGap) {
                s.cricketEnv = 1.0
                s.lastCricketAt = t
                s.nextCricketGap = (sampleRate * (2 + Random.nextDouble(0.0, 3.0))).toLong()
                s.cricketDur = (sampleRate * 0.05).toLong()  // 短促 50ms
                s.cricketElapsed = 0L
            }
            if (s.cricketEnv > 0.0) {
                val progress = (s.cricketElapsed.toDouble() / s.cricketDur).coerceAtMost(1.0)
                // 8kHz 高频脉冲 + 包络
                val env = sin(PI * progress) * 0.03
                sample += sin(2 * PI * 8000.0 * t / sampleRate) * env
                s.cricketElapsed++
                if (progress >= 1.0) s.cricketEnv = 0.0
            }
            out[i] = clamp(sample)
        }
    }

    private fun clamp(v: Double): Short {
        val scaled = (v * 32767).toInt()
        return scaled.coerceIn(-32768, 32767).toShort()
    }

    private companion object {
        const val TAG = "AmbientSynth"
    }
}

/**
 * 每个模式的状态机，避免协程间数据竞争（单协程内修改，安全）。
 */
private class SynthState(val mode: AmbientSynth.Mode) {
    var timeBase: Long = 0L

    // DAY
    var birdEnv: Double = 0.0
    var birdStartFreq: Double = 2000.0
    var birdEndFreq: Double = 4000.0
    var birdDur: Long = 0L
    var birdElapsed: Long = 0L
    var lastBirdAt: Long = -100000L
    var nextBirdGap: Long = 0L

    // RAIN
    var lpState: Double = 0.0
    var thunderEnv: Double = 0.0
    var thunderDur: Long = 0L
    var thunderElapsed: Long = 0L
    var lastThunderAt: Long = -100000L
    var nextThunderGap: Long = 0L

    // SNOW
    var hpState: Double = 0.0
    var hpPrevX: Double = 0.0
    var whistleEnv: Double = 0.0
    var whistleFreq: Double = 3000.0
    var whistleDur: Long = 0L
    var whistleElapsed: Long = 0L
    var lastWhistleAt: Long = -100000L
    var nextWhistleGap: Long = 0L

    // NIGHT
    var cricketEnv: Double = 0.0
    var cricketDur: Long = 0L
    var cricketElapsed: Long = 0L
    var lastCricketAt: Long = -100000L
    var nextCricketGap: Long = 0L
}

/**
 * 简单白噪声发生器，用线性同余法（够用，性能优先于严格随机性）。
 */
private class NoiseState {
    private var seed: Long = System.nanoTime() or 1L
    fun nextWhite(): Double {
        // LCG
        seed = seed * 6364136223846793005L + 1442695040888963407L
        // 取高位 16bit 作为 -1~1
        val raw = ((seed shr 33).toInt() and 0xFFFF) - 32768
        return raw / 32768.0
    }
}
