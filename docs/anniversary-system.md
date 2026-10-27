# 纪念日系统实现方案

## Context

现有陪伴 App（中野三玖）已有签到、每日 9:00 主动问候闹钟（`GreetingScheduler` + `GreetingAlarmReceiver` + `GreetingNotifier` + `GreetingManager`），但缺少"在一起第 X 天 / 用户生日 / 节日"这种有仪式感的触点。用户希望：

1. 在主页签到卡片**正下方**新增一张纪念日卡片，点击弹出详情对话框。
2. "在一起第 X 天"默认 = 首次聊天日期，用户可在设置里改。
3. milestone（第 1/100/200/365/500/730/1000... 天）、用户生日、节日 → 触发专属台词 + 系统推送。
4. 复用现成的 `GreetingScheduler` 9:00 闹钟基建，不新增 AlarmManager / Receiver / Manifest 权限。
5. 节日范围 = 公历节日 + 中国农历节日（春节/元宵/端午/七夕/中秋/重阳）。
6. milestone 推送只在 milestone 当天触发，日常不重复打扰。

目标产出：用户能在签到下方看到"在一起第 X 天"卡片，点击可看倒计时与编辑入口；设置里有"纪念日 / 我的生日"两项；milestone/生日/节日当天 9:00 会收到一条带专属台词的通知与聊天消息；重启后闹钟仍生效。

## 复用与新增总览

**复用（不改）：** `GreetingScheduler` / `GreetingAlarmReceiver` / `BootReceiver` / `GreetingNotifier` 的通知渠道 `chat_greeting` 与 `ensureNotificationChannel` / `sendGreetingNotification` 文案生成模式 / `SettingsManager.isGreetingEnabled` 开关（纪念日推送同样受主动问候总开关约束）。

**新增文件（5 个）：**
- `app/src/main/java/com/example/eden/AnniversaryManager.kt` — SharedPreferences 单例，存纪念日起点 / 用户生日 / 当日已推送达标记，镜像 `GreetingManager` 的日期级去重模式。
- `app/src/main/java/com/example/eden/FestivalCalendar.kt` — 公历 + 农历节日名查询。封装"今天是哪个节日"。
- `app/src/main/java/com/example/eden/LunarConverter.kt` — 200 年查找表算法（1900–2100），`gregorianToLunar(y, m, d) -> LunarDate`。无三方依赖。
- `app/src/main/java/com/example/eden/AnniversaryNotifier.kt` — `checkAndSend(context)` suspend 入口，镜像 `GreetingNotifier.checkAndSendGreeting` 的"登录校验 → 开关校验 → 生成文案 → 写 ChatRepository → 发通知 → 标记已发"流程。
- `app/src/main/java/com/example/eden/AnniversaryDialog.kt` — 首页卡片点击后的详情对话框，展示"在一起第 X 天"、下个 milestone 倒计时、今日是否生日/节日，并提供"编辑纪念日"按钮（DatePickerDialog）。

**修改文件（7 个）：**
- `app/src/main/java/com/example/eden/character/MikuPersona.kt` — 新增 `ANNIVERSARY_LINES: List<String>`、`BIRTHDAY_LINES: List<String>`、`FESTIVAL_LINES: Map<String, List<String>>`（key = 节日名，与 `FestivalCalendar` 返回的字符串对齐）。
- `app/src/main/java/com/example/eden/data/ChatMessageDao.kt` — 新增 `@Query("SELECT timestamp FROM chat_message WHERE characterId = :characterId ORDER BY timestamp ASC, id ASC LIMIT 1") suspend fun firstMessageTimestamp(characterId: String): Long?`。
- `app/src/main/java/com/example/eden/data/ChatRepository.kt` — 新增 `suspend fun firstChatTime(characterId: String): Long?` 薄封装。
- `app/src/main/java/com/example/eden/GreetingReceivers.kt` — `GreetingAlarmReceiver.onReceive` 协程里，在 `checkAndSendGreeting` 之后追加 `AnniversaryNotifier.checkAndSend(context)`（独立 try/catch，纪念日推送失败不能影响次日闹钟续接）。
- `app/src/main/java/com/example/eden/MainActivity.kt` — `initViews` 绑定 `cardAnniversary`/`tvAnniversaryText`/`btnAnniversaryEntry`；`onResume` 调 `refreshAnniversaryStatus()`（刷新卡片文本，当天是 milestone/生日/节日时高亮）；`setupListeners` 绑定点击 → `showAnniversaryDialog()`。
- `app/src/main/res/layout/activity_main.xml` — 在 `card_sign_in` 后面插入 `card_anniversary` CardView，`app:layout_constraintTop_toBottomOf="@id/card_sign_in"`，结构镜像 `card_sign_in`（CardView → LinearLayout[id=btn_anniversary_entry] → ImageView + TextView[id=tv_anniversary_text]）。
- `app/src/main/java/com/example/eden/SettingsActivity.kt` + `app/src/main/res/layout/activity_settings.xml` — 新增"纪念日"和"我的生日"两个设置项，点击弹 `DatePickerDialog`，写入 `AnniversaryManager`。镜像 `showNicknameDialog()` 的 AlertDialog 包装方式。

`AndroidManifest.xml` 不改：复用同一 `GreetingAlarmReceiver`，无需新 receiver / 新权限（`SCHEDULE_EXACT_ALARM` / `RECEIVE_BOOT_COMPLETED` 已声明）。

## 关键实现要点

### AnniversaryManager（SharedPreferences 镜像 GreetingManager）

```
PREFS_NAME = "anniversary_prefs"
KEY_START_DATE   = "anniversary_start_date"     // "yyyy-MM-dd"，空 → 回落到首次聊天日期
KEY_USER_BIRTHDAY = "user_birthday"            // "yyyy-MM-dd"，空 → 不发生日推送
KEY_LAST_PUSH_DATE = "last_push_date_<kind>"  // kind ∈ {milestone, birthday, festival:<name>}，日期级去重
```

提供：
- `getStartDate(context): String?` / `setStartDate(context, yyyy-MM-dd)`
- `getUserBirthday(context): String?` / `setUserBirthday(context, yyyy-MM-dd)`
- `daysSinceStart(context, firstChatTime: Long?): Int?` — 起点日期到今天的天数；起点为空且 firstChatTime 为空时返回 null（卡片显示"还没开始的故事"）
- `shouldPush(kind: String, today: String): Boolean` / `markPushed(kind: String, today: String)` — 镜像 `GreetingManager.shouldSendMorningGreeting` 的去重模式

### FestivalCalendar（公历 + 农历）

```
SOLAR = mapOf(
    "1-1" to "元旦", "2-14" to "情人节", "3-14" to "白色情人节",
    "10-31" to "万圣节", "12-24" to "平安夜", "12-25" to "圣诞", "12-31" to "跨年"
)
LUNAR = mapOf(
    "1-1" to "春节", "1-15" to "元宵", "5-5" to "端午",
    "7-7" to "七夕", "8-15" to "中秋", "9-9" to "重阳"
)
fun todayFestival(calendar: Calendar): String?  // 先查 SOLAR，再查 LUNAR（经 LunarConverter）
```

### LunarConverter（200 年查找表）

经典 LunarInfo 算法：用 `long[200]` 数组存 1900–2099 每年的闰月位 / 大小月信息，按累计天数减年差换算。这是 `ChineseCalendar` 标准库的等价实现，无三方依赖。输出 `LunarDate(year, month, day, isLeapMonth)`。

### MikuPersona 新增台词池（风格对齐现有 `FIXED_REPLY`）

```
ANNIVERSARY_LINES = listOf(
    "今天是我们认识第 {DAYS} 天呢……（偷偷数着日子）",
    "第 {DAYS} 天了。我有在好好记着哦。",
    "……{DAYS} 天了。谢谢你一直在我身边。"
)
BIRTHDAY_LINES = listOf(
    "……生日快乐。（脸红低头）我又没准备什么……",
    "生日快乐……今天，全部时间都给你。"
)
FESTIVAL_LINES = mapOf(
    "春节" to listOf("新年快乐……（鞠躬）这一年也请多多关照。", "爆竹声太响……耳机差点掉了。"),
    "中秋" to listOf("月亮很圆……要不要一起看？我耳机里在放赏月广播。", "中秋快乐……月饼，你吃了吗？"),
    "圣诞" to listOf("圣诞快乐……（手指卷着耳机线）袜子我有挂好。", "……Merry Christmas。"),
    // 其余节日同款补全
)
```

`{DAYS}` 占位由 `AnniversaryNotifier` 替换，镜像 `MikuPersona.systemPrompt` 的 `{USER_NAME}` 替换模式。

### AnniversaryNotifier.checkAndSend

流程严格镜像 `GreetingNotifier.checkAndSendGreeting`：
1. `AuthManager.isLoggedIn` / `SettingsManager.isGreetingEnabled` 校验（同一总开关，免得关掉主动问候还能收到纪念日推送）。
2. 算 `daysSinceStart`；判定今日命中：
   - `days in MILESTONES (1, 100, 200, 365, 500, 730, 1000, 1500, 2000, 3000, 5000) || days % 1000 == 0` → milestone
   - 用户生日月日 == 今天月日 → birthday
   - `FestivalCalendar.todayFestival(now) != null` → festival（取节日名作为 `kind` 去重 key）
3. 对每个命中项：`shouldPush(kind, today)` 已推过则跳过；否则从对应台词池取一条（`{DAYS}` 替换后），调 `ChatRepository(context).append(CHARACTER_ID, ChatMessage(content = msg, type = TYPE_RECEIVED))`，再 `sendGreetingNotification` 同款方式发通知，**复用 `GreetingNotifier.CHANNEL_ID`，但用独立 NOTIFICATION_ID = 1002**，避免和早安通知相互覆盖。
4. 命中后 `markPushed(kind, today)`。

注意：milestone 与生日、节日可能同一天。按"milestone → birthday → festival"顺序各发一条（每条用不同 kind 去重），不做合并，让仪式感分明。

### MainActivity 卡片与刷新

`refreshAnniversaryStatus()`：
- 读 `AnniversaryManager.daysSinceStart(this, firstChatTime)`；null → 显示"还没开始的故事"
- 命中 milestone/生日/节日当天 → 文本前缀加 "♡" 且染粉色 `0xFFFF4081`
- 平日 → 灰色 `0xFF888888`

`firstChatTime` 通过 `ChatRepository(this).firstChatTime(MikuPersona.ID)` 在 `onResume` 协程里取，缓存到字段，避免每次开 Room 查询。

`AnniversaryDialog`：复用现有 `AlertDialog.Builder` 模式（参考 `SignCalendarDialog` / `showNicknameDialog`）。展示：
- 「你们已经在一起 X 天啦」
- 「距下个 milestone（第 N 天）还有 Y 天」
- 今日若命中生日/节日 → 加一行"今天是你的生日 / 今天是春节"
- 「编辑纪念日」按钮 → `DatePickerDialog` → `AnniversaryManager.setStartDate`，写入后立即刷新
- 「编辑生日」按钮 → 同款

### SettingsActivity 设置项

`activity_settings.xml` 在"设置称呼"项下方加两项 `item_anniversary` / `item_birthday`，结构与 `item_nickname` 一致。`SettingsActivity.setupListeners` 绑定 → 弹 `DatePickerDialog`，确定后写 `AnniversaryManager` 并 Toast "已保存"。设置变更后立即重排当日闹钟无意义（闹钟 9:00 准点已固定），但要让用户在 `onResume` 回到主页时看到卡片更新。

## 验证

端到端手动验证（依赖改系统时钟，无单元测试基础设施）：

1. **首次聊天日期回退**：清空 `anniversary_prefs` + `chat_message` 表，卡片应显示"还没开始的故事"；发一条聊天后再回主页，卡片应显示"在一起第 1 天"。
2. **milestone 推送**：把系统时钟拨到"首次聊天日 + 100 天" 8:59，等 9:00 触发，应收到 milestone 通知 + 聊天记录出现一条三玖台词；同一天再拨到 9:01 重启 App 不应重复推送（去重生效）。
3. **生日推送**：在设置里填生日 = 今天，拨时钟到次日 8:59 再回 9:00 触发，应收到生日通知。
4. **节日推送（农历）**：把系统时钟拨到下一个春节公历日期 8:59 → 9:00，应收到春节专属台词。
5. **BootReceiver 覆盖**：触发 milestone 后重启设备，进 App 后次日 9:00 闹钟应仍能触发（无需重新打开 App）。
6. **卡片点击 → 对话框**：点击卡片弹详情；点"编辑纪念日"改日期后返回，卡片文本与倒计时应即时刷新。
7. **总开关联动**：关掉"主动问候"开关，milestone/生日/节日当天 9:00 应**不**推送（与早安问候同受 `isGreetingEnabled` 约束）。
8. **编译与静态检查**：`./gradlew assembleDebug` 通过；新增 `firstMessageTimestamp` 在数据库无记录时返回 null（DAO 测试用 `@Query` 返回 nullable `Long?`）。

## 范围外（不在本次实现）

- 节日列表用户自定义（已选"公历+农历预置"方案，用户自定义节后做）。
- 纪念日独立 Activity（已选"弹对话框"方案）。
- 推送时间用户可配置（沿用 9:00 固定时段；如要可配置，需新增独立 AlarmManager，超出"复用现成基建"范围）。
- 节日礼物 / 好感度奖励联动（与好感度系统改造分开做）。
