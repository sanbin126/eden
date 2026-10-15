# Eden 音乐（BGM）功能实现方案

## Context

Eden 是一个陪伴型角色 App（角色"中野三玖"）。主界面 [MainActivity.kt:167](file:///c:\Users\suwei\Desktop\eden\app\src\main\java\com\example\eden\MainActivity.kt#L167) 的 `btnMusic` 当前只弹 toast"音乐功能开发中"。需要接入实际播放器：

- 用户对版权敏感 → 不能打包受版权保护的曲子（包括任何 Vocaloid / 动漫 OP/ED / 流行歌曲）
- 用户希望"配合主题" → 复用现有 [WeatherManager](file:///c:\Users\suwei\Desktop\eden\app\src\main\java\com\example\eden\WeatherManager.kt) 输出的天气状态切氛围
- 用户已确认方案：**底部弹窗选择入口** + **按天气切氛围** + **代码实时合成环境音（AudioTrack）**，且保留 mp3 替换接口（用户后续可用 Suno 生成 `bgm_day.mp3` 等丢进 `res/raw/` 替换，无需改代码）

最终实现"0 版权 + 立即跑通 + 配合天气氛围切换 + 可被 Suno mp3 替换"的内置 BGM，同时支持选择本地音乐文件播放（侵权责任在用户）。

## 架构设计

### 核心组件

| 组件 | 类型 | 职责 |
|---|---|---|
| `MusicService.kt` | 前台 Service | 持有 `AudioTrack`（合成 BGM）+ `MediaPlayer`（本地文件 / res/raw mp3）；维护前台通知 + 控制按钮；接收 Intent 切换播放源 / 状态 |
| `MusicManager.kt` | object 单例 | 暴露 `startAmbientByWeather(ctx)` / `startLocalFile(ctx, uri)` / `pause` / `resume` / `stop`；维护当前播放模式 + 当前天气；映射天气→氛围模式 |
| `AmbientSynth.kt` | 类 | 用 `AudioTrack` MODE_STREAM 实时合成 PCM，4 种模式（晴/雨/雪/夜）；用协程在 IO 调度器循环填 buffer |
| `MusicPickerDialog.kt` | BottomSheetDialogFragment | 底部弹窗，3 个选项：内置环境音 / 选择本地音乐 / 停止 |

### 文件清单

**新建：**
- `app/src/main/java/com/example/eden/MusicService.kt`
- `app/src/main/java/com/example/eden/MusicManager.kt`
- `app/src/main/java/com/example/eden/AmbientSynth.kt`
- `app/src/main/java/com/example/eden/MusicPickerDialog.kt`
- `app/src/main/res/drawable/ic_pause.xml`、`ic_play.xml`、`ic_skip_next.xml`、`ic_skip_prev.xml`（通知栏控制图标，若不存在）

**修改：**
- `app/src/main/AndroidManifest.xml`：注册 Service、补权限
- `app/src/main/java/com/example/eden/MainActivity.kt`：btnMusic 改弹底部弹窗；申请 `READ_MEDIA_AUDIO`（API 33+）+ `POST_NOTIFICATIONS`；注册 `OpenDocument` launcher

**不动：**
- `app/src/main/res/layout/activity_main.xml`（btnMusic 已是 ImageButton 56dp + ic_music）
- 现有 `WeatherManager.kt`（只读取它的 `WeatherResult`）

## 关键实现细节

### 1. AmbientSynth —— 实时合成环境音

- 采样率 22050Hz / 单声道 / 16bit PCM，MODE_STREAM
- Kotlin 协程在 `Dispatchers.IO` 跑合成循环，每次填 1024 样本推给 AudioTrack
- 4 种氛围模式（由 MusicManager 根据 WeatherManager.WeatherResult.text 映射）：

| 模式 | 触发天气 | 合成内容 |
|---|---|---|
| `DAY` | 晴/多云/阴/雾 | 低频正弦基底 200Hz + 间歇性高频"鸟鸣"扫频（2000-4000Hz，3-8s 随机） |
| `RAIN` | 小雨/雨/阵雨/雷雨 | 白噪声 + 低通滤波模拟雨声；雷雨加 80Hz 衰减包络"轰"（30-60s 一次） |
| `SNOW` | 雪 | 白噪声 + 高通滤波模拟风声；偶发高频"哨"扫频 |
| `NIGHT` | 系统时间 22:00-06:00 强制覆盖 | 极低频 100Hz 嗡基底 + 间歇 8000Hz 虫鸣脉冲（2-5s 一次） |

- 全局音量偏低（陪伴不打扰），约 -20dB
- pause = `audioTrack.pause()`；resume = `audioTrack.play()`；stop = `audioTrack.stop() + release()`

### 2. mp3 替换接口

MusicService 启动播放前先检测对应天气的 res/raw 是否有 mp3：
- `bgm_day.mp3` / `bgm_rain.mp3` / `bgm_snow.mp3` / `bgm_night.mp3`
- **有 → 用 MediaPlayer 播放该 mp3 循环**（`isLooping = true`）
- **无 → 用 AmbientSynth 实时合成**

用户后续用 Suno 生成同名 mp3 丢进 `res/raw/`，重启 App 即自动切换，无需改代码。

### 3. 前台通知 + 控制按钮

复用 [GreetingNotifier.kt:87-92](file:///c:\Users\suwei\Desktop\eden\app\src\main\java\com\example\eden\GreetingNotifier.kt#L87-L92) 的 `ensureNotificationChannel` 范式，新建独立渠道：
- CHANNEL_ID = `"eden_music"`
- 渠道名："Eden 背景音乐"
- importance = `IMPORTANCE_LOW`（陪伴不打扰）

通知内容：
- 标题："Eden · 陪伴中"
- 内容：当前播放源（如"晴天环境音 · 内置 BGM"或本地音乐文件名）
- 控制按钮（PendingIntent 广播给 MusicService）：上一首 / 播放-暂停 / 下一首
- 点击通知 → PendingIntent 回 MainActivity

### 4. 入口交互（底部弹窗）

复用 [ChatActivity.kt:75-77](file:///c:\Users\suwei\Desktop\eden\app\src\main\java\com\example\eden\ChatActivity.kt#L75) 的 `registerForActivityResult` 范式注册 `OpenDocument` launcher（MIME `audio/*`）。

点击 btnMusic → 弹 `MusicPickerDialog`（BottomSheetDialogFragment）：
- "播放内置环境音" → `MusicManager.startAmbientByWeather(this)`（按当前天气自动选模式）
- "选择本地音乐" → 启动 OpenDocument launcher；选完 → `MusicManager.startLocalFile(this, uri)`
- "停止"（仅当正在播放时显示） → `MusicManager.stop(this)`

### 5. 权限处理

复用 [ChatActivity.kt:338-344](file:///c:\Users\suwei\Desktop\eden\app\src\main\java\com\example\eden\ChatActivity.kt#L338) 的 `requestNotificationPermissionIfNeeded` 范式：
- Android 13+ (API 33+) 申请 `READ_MEDIA_AUDIO`
- 同时申请 `POST_NOTIFICATIONS`（确保前台通知能弹）
- Android 14+ (API 34+) 前台 Service 必须声明类型 → `FOREGROUND_SERVICE_MEDIA_PLAYBACK`

AndroidManifest.xml 增加权限：
```xml
<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
```

增加 Service 注册：
```xml
<service
    android:name=".MusicService"
    android:exported="false"
    android:foregroundServiceType="mediaPlayback" />
```

### 6. 主题（天气）切换逻辑

`MusicManager.startAmbientByWeather(context)`：
1. 调 `WeatherManager.getWeather(context)` 获取 `WeatherResult`（有 1 小时缓存，可接受每次启动重读一次）
2. 映射 weatherText → ambient 模式：
   - 含"雨"或"雷" → `RAIN`
   - 含"雪" → `SNOW`
   - 其他（晴/多云/阴/雾/未知） → `DAY`
3. 判断系统时间 22:00-06:00 → 强制 `NIGHT`（夜晚优先）
4. 启动 MusicService，传 Intent extra `ambient_mode`

**不实现自动监测切歌**：天气缓存 1 小时一次，每次启动 BGM 重读一次足够。如果以后要做"天气变化时自动切 BGM"，再加 WorkManager 周期任务，但当前需求不要求。

## 验证方法

1. **基础播放**：启动 App → 点 btnMusic → 弹底部弹窗 → 选"播放内置环境音" → 听到对应当前天气的 ambient 音
2. **前台 Service**：切到后台 / 锁屏 → BGM 继续播放，下拉通知栏看到"Eden · 陪伴中"通知 + 3 个控制按钮
3. **通知控制**：点"暂停" → 立即停；点"播放" → 恢复
4. **本地音乐**：选"选择本地音乐" → 弹文件选择器 → 选 mp3 → 切到 MediaPlayer 播放该文件，通知内容更新为文件名
5. **天气切换**：手动改 `weather_prefs` 中 `weather_text` 为"雨"→ 重启 BGM → 听到雨声
6. **昼夜覆盖**：测试时间设为 23:00 → 选内置 BGM → 听到夜氛围（即便天气是晴也走 NIGHT）
7. **mp3 替换**：把任意 mp3 重命名为 `bgm_day.mp3` 丢进 `res/raw/` → 重启 App → 选内置 BGM → 改用 MediaPlayer 循环播放该 mp3（不再用 AudioTrack 合成）
8. **权限缺失场景**：拒绝 `READ_MEDIA_AUDIO` → 选"选择本地音乐" → toast 提示"需要音频访问权限"，不崩溃
