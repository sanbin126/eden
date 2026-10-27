# 每日循环（Daily Loop）设计方案

> 本文只做设计，不含实现。先定「每日循环」，再谈加功能。

## Context

现有产出源已经不少，但彼此**没有节奏**，各自独立：

| 功能 | 现状入口 | 产出 | 出口 |
| --- | --- | --- | --- |
| 首开 | [MainActivity.onResume()](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/MainActivity.kt#L212-L228) | `DAILY_OPEN_YEN = 5` 円，每日一次 | 无（发完即止） |
| 签到 | [SignInManager.signToday()](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/ui/theme/SignInManager.kt#L51-L71) | 20~40 円（基础 20 + 连续 ×5，封顶 40） | 无 |
| 摸头 | [handleCharacterTouch()](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/MainActivity.kt#L520-L578) → [earnTouchYen()](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/MainActivity.kt#L584-L591) | +1 円/次，日上限 10，3s 冷却 | **无（死胡同）** |
| 聊天 | [ChatSession](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/chat/ChatSession.kt#L47-L59) | +2 円/条（日上限 30）；每 3 条 +1 好感（日上限 5） | 无 |
| 专注 | [FocusTimerManager.finishSegment()](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/FocusTimerManager.kt#L250-L286) | +10 円/段（日上限 60）；+1 好感/段（日上限 4） | 仅 [FocusNotifier](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/FocusNotifier.kt) 工具通知，**不是角色说话** |
| 小游戏 | 扫雷 / 五子棋 / 象棋 | **零产出** | **无（孤岛）** |
| 投喂 | [GiftManager.feed()](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/GiftManager.kt#L32-L44) | 消耗円换好感（+2~10，日/周限次） | 无（终点） |

**判断标准（本文贯穿使用）**：对任意功能问一句 ——「它把用户引向下一个动作了吗？」现在答案基本是「没有」：摸头是死胡同，小游戏是孤岛，签到发完币就断，专注结束只留一条工具通知。

**已有的可复用能力**：中野三玖其实**已经会主动找人**——[GreetingScheduler](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/GreetingScheduler.kt#L44-L47) + [GreetingNotifier](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/GreetingNotifier.kt#L69-L83) 已能"写聊天记录 + 推通知 + 点击进聊天页"。这条链路正是补环最省力的抓手。

**目标**：把「登录 → 签到 → 摸头 → 聊天 → 专注 → 投喂」串成一个**互为入口的闭环**，每一步都推进一点好感或円，并在结尾把用户推向下一步。

---

## 一、循环定义

一个节点 = **入口 + 动作 + 产出 + 出口钩子**。出口钩子就是"互为入口"的实现载体。

| 步 | 入口（现在 / 建议） | 产出 | 出口钩子 → 下一步 |
| --- | --- | --- | --- |
| 1 见面 | 启动 App（已有） | 5 円 | 未签到则高亮签到卡 + 气泡「今天也……先签个到吧？」→ 签到 |
| 2 签到 | 签到卡 / 弹窗（已有） | 20~40 円（建议 +1 好感） | 签到成功气泡「要不要……摸摸我？」→ 摸头 |
| 3 摸头 | 点立绘（已有） | 円（现状）+ **首摸 1 好感/天** | 摸到上限时说「光摸摸不够哦……说说话？」→ 聊天 |
| 4 聊天 | 聊天按钮（已有） | 円 + 好感（已有） | 产币触顶时说「攒够了，去专注一会儿？」或「我想吃点东西」→ 专注 / 投喂 |
| 5 专注 | 计时器（已有） | 円 + 好感（已有） | **专注完成 → 角色主动发一条聊天消息 + 通知点击进 ChatActivity** → 聊天 |
| 6 投喂 | 礼物卡（已有） | 好感（已有） | 回应台词留钩子「……明天，也来好吗？」→ 次日回到第 1 步（闭环收口） |

闭环的形状（每条箭头都是一次"引向下一个动作"）：

```
        登录 ──► 签到 ──► 摸头 ──► 聊天 ──► 专注
          ▲                    ▲        │        │
          │                    └────────┘        │
          │                                    （角色主动发消息）
          └──────── 投喂 ◄─────────────────────┘
                   （次日再来）
```

---

## 二、核心机制：DailyLoopManager

新增一个 SharedPreferences 单例，负责"今日走到哪一步"。**镜像 [SignInManager](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/ui/theme/SignInManager.kt) 的按天去重**与 [WalletManager](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/WalletManager.kt#L76-L108) 计数器的 period 思路。

```kotlin
enum class LoopStep(val id: String, val label: String) {
    LOGIN("login", "见面"),
    SIGN_IN("sign_in", "签到"),
    TOUCH("touch", "摸摸"),
    CHAT("chat", "聊天"),
    FOCUS("focus", "专注"),
    FEED("feed", "投喂"),
}
```

对外 API（全部按 `yyyy-MM-dd` 比较，跨天自动全新）：

- `markDone(context, step)` —— 幂等，重复调用不重复计数
- `isDone(context, step): Boolean`
- `nextStep(context): LoopStep?` —— 首个未完成步骤，驱动"回主页气泡"
- `todayProgress(context): Pair<Int, Int>` —— (已完成, 总数)，驱动进度条
- 持久化格式镜像 WalletManager 的 `{"period":"yyyy-MM-dd","steps":[...]}`

**关键约束**：循环进度是"本机当日状态"，**不参与 [SyncManager](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/SyncManager.kt)**，照搬 WalletManager 计数器的 local-only 先例，避免多设备互相拉扯。

**驱动方式**：任意子页面返回主界面时，[MainActivity.onResume()](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/MainActivity.kt#L212-L228) 读 `nextStep()`，用**已有的** [showHeadBubble()](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/MainActivity.kt#L1046-L1090) 说一句指向下一步的话。

### 指引机制（LoopGuide）怎么落地

「互为入口」不是靠孤立文案，而是一套**统一指引机制**，尽量不新增组件。一次指引 = **台词 + 高亮 + 进度** 三件套。

**① 数据源**：`DailyLoopManager.nextStep(context)` —— 首个未完成步骤，就是要指向的目标。

**② 表现三件套**（全部复用现成能力）：

| 层 | 做法 | 复用 |
| --- | --- | --- |
| 台词 | `showHeadBubble(guideLineFor(step))`，文案取自 MikuPersona 的「引导句池」 | [showHeadBubble()](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/MainActivity.kt#L1046-L1090) |
| 高亮 | 目标入口做一次 scale/alpha 呼吸动画（2~3 次后复位），把视线拉过去 | 现有 View 动画 |
| 进度 | 6 点指示器点亮到当前步 | `todayProgress()` |

**③ 锚点映射**（step → 主页已有入口，全部现成）：

| step | 指向台词 | 高亮锚点 |
| --- | --- | --- |
| SIGN_IN | 「先签个到吧？」 | `card_sign_in` |
| TOUCH | 「要不要……摸摸我？」 | `iv_character` |
| CHAT | 「陪我说说话？」 | `btn_start_chat` |
| FOCUS | 「陪我专注一会儿？」 | `btn_timer` |
| FEED | 「我有点饿了……」 | `btn_gift_entry` |
| LOGIN | （启动即完成，无需指引） | — |

**④ 两类触发时机**（覆盖"回主页"与"就地完成"）：
- **回主页兜底**：[onResume()](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/MainActivity.kt#L212-L228) 里，若 `nextStep` 与上次已提示的步骤不同 → 指引一次。
- **就地即时**：动作完成的回调里立刻指下一步 —— 签到成功→指摸头；摸到上限→指聊天；聊天触顶→指专注/投喂；投喂完成→台词留钩子（承接次日）。**专注→聊天**跨页，复用 [GreetingNotifier](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/GreetingNotifier.kt#L69-L83) 的「写库 + 通知 + 点击进 ChatActivity」。

**⑤ 与现有气泡互斥**：onResume 同一次只出一条，优先级沿用现有雏形并扩展为

> 里程碑提示 > 循环指引 > 每日自言自语

（现 [maybeShowDailySoliloquy](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/MainActivity.kt#L1003-L1023) 与 [maybeNudgeMilestone](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/MainActivity.kt#L1030-L1040) 已是互斥结构，把「循环指引」插在中间即可。）

**⑥ 去重**：记录"上次已指引的 step"，同一步骤不重复念；跨天重置。

---

## 三、逐节点设计（补环 vs 加功能）

### 1. 首开 → 签到（补环，改动最小）

- 现状：[onResume()](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/MainActivity.kt#L212-L228) 发 5 円 + `checkAndShowDailySignDialog()`。
- 改：未签到时**不给弹窗抢戏**，先让三玖用气泡指签到卡（签到卡加一次脉冲高亮），弹窗留给用户主动点。发完见面礼后 `markDone(LOGIN)`。
- 复用：签到卡、`checkAndShowDailySignDialog`、`showHeadBubble`。

### 2. 签到 → 摸头（补环）

- 现状：[signToday()](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/ui/theme/SignInManager.kt#L51-L71) 签完即止，只有一句暖心话（`nextSignInLine`）。
- 改：签到成功后气泡改为**指向性台词**（暖话轮转池加一类"引导句"），`markDone(SIGN_IN)`。
- 可选：签到 +1 好感，鼓励"早签"。

### 3. 摸头 → 聊天（补环，解决死胡同）

- 现状：[earnTouchYen()](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/MainActivity.kt#L584-L591) 只产円，摸完没有任何去向。
- 改：
  1. **首摸给 1 好感/天**：`WalletManager.tryDailyGrant(context, COUNTER_TOUCH_AFFECTION, 1)` → `AffectionManager.increase(1)`（跨里程碑走 `MilestoneManager.onCrossed`，与聊天/专注一致）。円产出维持现状（+1/次，日上限 10）。
  2. **摸到当日上限时触发出口**：气泡「今天的摸摸……够多了。要不要跟我说说话？」→ 视觉指向聊天按钮。
- 复用：已有的分区域台词（[handleCharacterTouch](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/MainActivity.kt#L520-L578)）、3s 冷却 + 日上限的 `earn` 机制。
- 对判断标准的回答：**现在摸头把用户引向聊天。**

### 4. 聊天 → 专注 / 投喂（补环）

- 现状：聊天产円 + 好感，无出口；且**聊天页完全没有投喂/专注入口**（grep 确认 [ChatActivity](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/ChatActivity.kt) 无 Gift/Focus 相关代码）。
- 改：
  1. 当 `WalletManager.dailyValue(COUNTER_YEN) >= YEN_DAILY_CAP`（聊天产币触顶）→ 角色一句「聊了这么久……要不要去专注一会儿？」。
  2. 聊天页底部加两个**轻入口**（只做跳转，不做全功能）：「去专注」→ `FocusTimerActivity`，「投喂」→ 复用主界面的 `showGiftPicker` 面板。
- 复用：`WalletManager.dailyValue`、`FocusTimerActivity`、[showGiftPicker()](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/MainActivity.kt#L341-L345)。

### 5. 专注 → 聊天（关键补环）

- 现状：[finishSegment()](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/FocusTimerManager.kt#L250-L286) 只调 [FocusNotifier.notifyFocusComplete()](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/FocusNotifier.kt#L32-L40) —— 这是**工具提醒**（"去休息一下"），点击进 `FocusTimerActivity`，**不是角色说话**。
- 改：专注结束（FOCUS 段）后，**让三玖主动找你说话**：
  - 复用 [GreetingNotifier](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/GreetingNotifier.kt#L69-L83) 那套「`ChatRepository.append(一条 TYPE_RECEIVED)` + 发通知」的模式（同一份 Room、同一 `chat_greeting` 渠道），文案换成专注专属（如「刚才那么专心……我都不敢出声。辛苦了。」）。
  - **通知点击目标改为 `ChatActivity`**（而非 FocusTimerActivity），把用户直接引回聊天。
  - 落地位置：`finishSegment` 的 `if (mode == FocusMode.FOCUS)` 分支，在 `notifyFocusComplete` 之后追加一条。
- 复用：`ChatRepository.append`、`MikuPersona` 台词池（新增 `FOCUS_DONE_LINES`）。
- 对判断标准的回答：**专注结束后角色主动找你，把你引回聊天。**

### 6. 投喂 → 次日（闭环收口）

- 现状：[GiftManager.feed()](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/GiftManager.kt#L32-L44) 是终点，只弹 `item.lines`。
- 改：**纯文案层**——在 [GiftCatalog](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/GiftCatalog.kt) 的台词池里，每个物品加 1 条**留钩子**的回应（「……谢谢。明天，也来好吗？」），不改任何逻辑。
- 这是"把用户推向明天再来"，即回到循环起点，闭环收口。

### 7. 小游戏 → 聊天 / 投喂（加功能，但必要）

- 现状：[GomokuActivity](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/GomokuActivity.kt) / [MinesweeperActivity](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/MinesweeperActivity.kt) / [XiangqiActivity](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/XiangqiActivity.kt) **零产出、零引导**。
- 改：小游戏结束时**按结果结算 円 + 好感**，统一走封顶 API：
  - **円：胜 +10 / 负 +5**；`WalletManager.earn(context, COUNTER_GAME_YEN, amount, dailyCap)`，**日上限 50 円**。
  - **好感：+3（输赢都 +3），日上限 3**；`WalletManager.tryDailyGrant(context, COUNTER_GAME_AFFECTION, 3)` → `AffectionManager.increase(3)`，每天首次结算才给好感。
  - **中途退出 / 放弃：不结算**（避免零成本刷）。
- 并给出口气泡引向聊天 / 投喂。
- 判定：这是"加功能"，但因它是孤岛，不加则循环有洞。

---

## 四、数值与节奏

当前经济：单日円产出约 `5 + (20~40) + 10 + 30 + 60 = 125~165`，再加小游戏（胜 10 / 负 5，**日上限 50**）。投喂消耗为 12（饮料）/ 30（食物）/ 80~120（礼物）。**投喂是円的唯一 sink**，所以循环天然是"白天攒、晚上花"。

好感产出：聊天 ≤5/日、专注 ≤4/日，加**摸头首摸 +1**、**小游戏 +3（日上限 3）**，投喂另计。好感来源共 5 条，权重仍以聊天 / 投喂为主。

用**已有的时段锚点**把循环映射到一天，避免新增闹钟：

| 时段 | 现有触发 | 期望循环动作 |
| --- | --- | --- |
| 8:00–11:00 | 早安推送（[checkAndSendGreeting](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/GreetingNotifier.kt#L33-L60)） | 登录 + 签到 |
| 12:00–20:00 随机 | 想你推送（`KIND_THOUGHT`） | 摸头 + 聊天 |
| 工作时段 | 用户自驱 | 专注 →（结束）角色回聊天 |
| 傍晚 | 聊天触顶提示 | 投喂 |
| 22:00 | 晚安推送（`KIND_GOODNIGHT`） | 收尾「明天见」 |

**原则**：同一时段只推**一个**动作。现有代码已有互斥雏形（[maybeShowDailySoliloquy](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/MainActivity.kt#L1003-L1023) 与 [maybeNudgeMilestone](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/MainActivity.kt#L1030-L1040) 互斥），循环提示要**接入同一套优先级**，避免四句台词抢戏：

> 里程碑提示 > 循环提示 > 每日自言自语

---

## 五、进度反馈与「产出说明」口径（UI）

### 1. 今日循环指示器（唯一的新 UI）

主界面加一个「今日循环」指示器（6 个点 / 一条进度条），每完成一步点亮。位置建议紧挨签到卡。

- 数据源：`DailyLoopManager.todayProgress()`。
- 作用：让用户一眼看到"还差投喂"，形成"今天还没走完"的**轻度牵引**，而不是靠推送硬拉。
- 实现：初期可用 MainActivity 里一段 `LinearLayout` + 6 个 `View`，不必自定义 View 类。

### 2. 好感 / 円 的产出怎么让用户知道？（集中说明页，不做公告弹窗）

**不做启动公告弹窗**（一次性弹窗没人读，还打断"首开 → 签到"的节奏），**也不在每次产出时用气泡报数字**（会让角色台词"工具化"，削弱情感感）。改为：

- **集中说明页**：设置里加「成长说明」入口，一张表列全 —— 円的 5 个来源 + 每日上限、好感的 5 个来源 + 每日上限。给愿意深究的玩家，随时可查，不打断流程。
- **气泡只负责"指路"，不负责"报数"**：导航台词保持角色感（「要不要摸摸我？」），产出数字统一去说明页看。

> 代价：单次产出缺少即时数字反馈；用「进度指示器 + 说明页」两个常驻出口补偿。

---

## 六、边界与可靠性

1. **跨天**：所有 `markDone` 按 `yyyy-MM-dd` 比较；跨天读取即视为全新周期（复用 SignInManager / WalletManager 的 period 模式）。
2. **去重 / 防刷**：摸头沿用 3s 冷却 + 日上限；小游戏奖励**必须**走 `earn(counter, amount, cap)` 封顶，禁止裸 `grant`。
3. **多设备**：循环进度 local-only，不入 SyncManager。
4. **通知节流**：循环提示与现有 4 类推送共用 `SettingsManager.isGreetingEnabled` / `isNotificationEnabled` 开关；每时段只推一个。
5. **不打扰**：循环提示优先级最低（见第四节），里程碑/剧情永远优先。
6. **离线**：循环提示是本地文案，不依赖 LLM；断网也能走完环。

---

## 七、复用 vs 新增总览

**复用（逻辑不改）**：`WalletManager`（earn/tryDailyGrant/计数器）、`AffectionManager`、`SignInManager`、`GiftManager`、`FocusTimerManager`、`ChatRepository.append`、`GreetingScheduler/GreetingNotifier`（时间段 + 写库 + 通知链路）、`SettingsManager`、`MilestoneManager`、`showHeadBubble`。

**新增（1 个类，尽量唯一）**：
- `app/src/main/java/com/example/eden/DailyLoopManager.kt` —— 循环步进与进度，SharedPreferences。

**修改（小改，逐个可控）**：
- `MainActivity.kt` —— 接入 `nextStep` 气泡 + 进度条 + 互斥优先级。
- `FocusTimerManager.kt` —— FOCUS 分支追加"角色主动发一条聊天消息"。
- `ChatActivity.kt` + `activity_chat.xml` —— 底部加「去专注 / 投喂」两个跳转入口。
- `GomokuActivity.kt` / `MinesweeperActivity.kt` / `XiangqiActivity.kt` —— 结束时结算一次 `earn`。
- `MikuPersona.kt` —— 新增 `FOCUS_DONE_LINES` 与"引导句"台词池。
- `GiftCatalog.kt` —— 每件物品加 1 条"留钩子"回应。

---

## 八、落地顺序（先补环，再谈加功能）

- **P0｜补环（改动小、见效最快）**：LoopGuide 指引机制 + 互斥优先级；专注→聊天；摸头→聊天（首摸 1 好感 + 出口钩子）；`DailyLoopManager` + 主页进度指示器。
- **P1｜填孤岛**：三个小游戏的结算（円 胜 +10/负 +5 日上限 50；好感 +3 日上限 3；放弃不结算）与出口。
- **P2｜加功能**：聊天页「去专注/投喂」入口、投喂留钩子文案、循环时段与推送映射。

---

## 九、决策结论

**已定：**

1. **循环顺序**：**自由顺序**，不强制。只做进度提示（6 点指示器），避免"打卡感"破坏情感向调性。
2. **摸头**：円产出维持现状；**首摸 +1 好感/天**；摸到上限触发"→ 聊天"出口。
3. **小游戏奖励**：**円 胜 +10 / 负 +5，日上限 50**；**好感 +3（输赢都是），日上限 3**；**中途退出 / 放弃不结算**。
4. **说明口径**：**只做设置内「成长说明」页**，不做公告弹窗、气泡不报数字（详见第五节）。
5. **小游戏好感日上限**：**3/日**（每天首次结算给 +3）。

**仍待确认：**

6. ~~是否需要「未完成循环」的挽回推送？~~ —— **已定：不做**，靠主页进度条自然牵引。
