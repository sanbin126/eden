# 全面离线化（本地优先同步）设计方案

> 本文只做设计，不含实现。评审通过后再按本文落代码，实施文档单独产出（只记录文件落点与改动清单）。

## Context

用户的实际问题：**卸载重装后头像、金币等数据不见了；而且我有时候没有启动后端。**

已经通过读代码 + 只读查询 `backend/app/eden.db` 确认的事实：

| 数据 | 现在的存储位置 | 是否上后端 | 重装后 |
| --- | --- | --- | --- |
| 头像 | `filesDir/profile_avatar_u<id>.jpg` | ❌ 不参与同步 | 必丢 |
| 金币 | `wallet_prefs.yen_balance` | ✅ `wallet` 文档 | 后端启动 + 同账号登录后回来 |
| 称呼 / 昵称 / 签名 / 开关 | `settings_prefs` | ✅ `settings` 文档 | 同上 |
| 好感 / 签到 / 聊天 / 日记 | 各自本地 + 后端文档 | ✅ | 同上 |
| 剧情进度 / 里程碑 / 世界状态 / 专注任务 | `story_prefs_*` / `milestone_prefs` / `world_prefs_*` / Room | ❌ | 必丢 |
| 主题皮肤 | 设备级 `eden_skin`（未走账号分区） | ❌ | 必丢 |

数据库实测（账号 1 = `1914236264@qq.com`）里 `wallet`、`settings`、`affection`、`signin`、`chat:miku` 都在，所以金币本身**不是没存进数据库**，而是「重装后没连上后端 / 登录成了另一个账号 / 后端没启动」导致拉不回来。

核心矛盾有两条，本设计分别解决：

1. **同步是「可选项」**：改动是否上传取决于当时进程是否活着、后端是否可达，进程被杀或后端没启动，改动就只剩本地一份。
2. **同步范围是「白名单」**：头像、剧情进度等压根不在同步 key 里，后端再稳定也回不来。

**目标：本地是唯一事实来源，后端只是备份。后端没启动时 App 完全可用，改动不丢，后端恢复后自动补传。**

---

## 一、业务目标与非目标

### 本期目标

1. 头像纳入同步（独立 `avatar` 文档，base64）。
2. 登录拉取后补传「本地有、远端没有」的 key（现在只遍历远端已有文档，见 [SessionManager.pullAll()](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/core/session/SessionManager.kt#L253-L266)）。
3. 待上传队列持久化（现在只有内存 `pendingKeys`，见 [SessionManager.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/core/session/SessionManager.kt#L196-L200)）。
4. 网络恢复 / 进程重启后自动补传（WorkManager，依赖已在 [build.gradle.kts](file:///c:/Users/suwei/Desktop/eden/app/build.gradle.kts#L200-L201)）。
5. 同步范围扩展：剧情进度、里程碑、世界状态、专注任务清单。
6. 主题皮肤由「设备级」改为「账号级」并纳入同步（用户已确认）。

### 非目标（本期不做）

- 不做离线注册 / 离线登录：重装后首次登录仍必须联网且后端可达（token 在 EncryptedSharedPreferences，卸载即丢）。
- 不改后端表结构：继续复用 `user_data(user_id, data_key, value, updated_at)`，后端**零改动**。
- 不重构现状依赖方向（`core/session` 反向 import 业务模块），只沿用既有 `exportDoc/importDoc` 分支。
- 不做多端实时同步 / WebSocket / 增量拉取。
- 不做服务端时间戳权威（仍用客户端毫秒/秒时间戳），已知风险见 §八。
- **不实现「同账号只能一台设备在线」的强制踢下线**（用户已确认为使用约定）。因此冲突仍按 LWW 处理，代码不新增设备标识 / token 版本号。

---

## 二、复用清单与禁止重写清单

### 复用清单（必须复用，不许另起一套）

| 能力 | 现有落点 |
| --- | --- |
| 登录态、userId、bearer token | [SessionManager.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/core/session/SessionManager.kt#L41-L112) |
| 同步 key 常量 | [SyncModels.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/core/session/SyncModels.kt#L36-L50) |
| key ↔ 本地存储映射 | `SessionManager.exportDoc / importDoc / applyRemoteDoc` |
| LWW 合并与本地时间戳 | `SessionManager.localUpdatedAt / setLocalUpdatedAt / importDoc` |
| 账号分区存储 | [EdenStorage.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/core/storage/EdenStorage.kt#L38-L57) |
| 拉取 / 上传 API 与鉴权 | `AuthClient.api.pullSyncData / pushSyncData` |
| 各业务自身序列化 | `ProfileRepository.exportJson/importJson`、`EconomyManager.exportWalletJson` 等 |
| WorkManager 运行时 | `libs.androidx.work.runtime.ktx`（已有依赖） |
| 网络请求重试（仅 408/429/5xx/IOException，3 次指数退避） | `core/network` 的 `RetryInterceptor` |

### 禁止重写清单

- 禁止另建同步队列 / 同步服务 / 第二套 LWW。
- 禁止改 `user_data` 表结构或新增同步接口。
- 禁止绕过 `EdenStorage` 直接 `getSharedPreferences("xxx")` 新增业务存储。
- 禁止在 `importDoc` 路径里回调 `notifyChanged`（会形成上传回环，现有注释已明确）。
- 禁止用 catch-all 静默吞掉上传失败（必须落日志并保留队列）。

---

## 三、模块职责与依赖方向

```
feature/*  ──notifyChanged()──►  core/session/SessionManager  ──►  AuthClient.api（后端）
data/repository/* ◄──export/import──┘            │
                                                 ├──►  sync_meta_prefs（时间戳 + 持久队列，按账号分区）
ui/theme, feature/*（UI）                        └──►  WorkManager ──► SyncWorker ──► SessionManager.flush()
```

| 模块 | 职责（本期新增/变更部分） |
| --- | --- |
| `SessionManager` | 仍是唯一编排点：入队、持久化、LWW、批量 push、登录后补传。新增 `enqueue/flush/backfill` 私有实现，**不新建编排类** |
| `SyncKeys` | 新增 `AVATAR / STORY / MILESTONE / WORLD / FOCUS_TASKS` |
| `ProfileRepository` | 新增 `exportAvatarJson / importAvatarJson`（编解码 + 落盘），`saveAvatar` 后 `notifyChanged(AVATAR)` |
| `StoryProgressRepository` | 新增 `exportJson / importJson`（汇总所有线路 prefs） |
| `MilestoneRepository` | 新增 `exportJson / importJson` |
| `WorldStateStore` | 新增 `exportJson / importJson`（所有 `world_prefs_*`） |
| `FocusRepository` | 新增 `exportTasksJson / importTasksJson`（仅任务列表，见 §六） |
| `ChatRepository` + `ChatMessageDao` | 新增「列出有聊天记录的角色 id」，供补传枚举 `chat:*` |
| `SyncWorker`（新增，唯一新类） | `CoroutineWorker`，网络约束 `CONNECTED`，只调用 `SessionManager.flush()` 并根据结果返回 `Result.retry/failure/success` |
| 后端 | 不改 |

依赖方向说明：保持现状（`core/session` 依赖业务模块）。这只是把新 key 加进现有 `when` 分支，不新增反向依赖层次。

---

## 四、同步 key 注册表

| key | 内容 | 本地来源 | 本期 |
| --- | --- | --- | --- |
| `settings` | 称呼三槽 / 昵称 / 签名 / 通知开关 / 问候开关 | `settings_prefs` | 已有 |
| `wallet` | 円余额 | `wallet_prefs` | 已有 |
| `affection` | 好感度 | `affection_prefs` | 已有 |
| `signin` | 签到日期集合（并集合并） | `sign_in_prefs` | 已有 |
| `anniversary` | 纪念日 / 生日 | `anniversary_prefs` | 已有 |
| `diary` | 日记 | Room `diary` | 已有 |
| `chat:<characterId>` | 聊天记录 + 记忆 | Room `chat_message` / `chat_memory` | 已有，本期补「枚举 + 补传」 |
| `avatar` | 头像图片（base64） | `filesDir/profile_avatar_u<id>.jpg` | **新增** |
| `story` | 全部线路剧情进度 | `story_prefs_<lineId>` | **新增** |
| `milestone` | 里程碑解锁/完成/记录 | `milestone_prefs` | **新增** |
| `world` | 角色世界状态 | `world_prefs_<characterId>` | **新增** |
| `focus_tasks` | 专注任务清单（不含运行中计时器） | Room `focus_task` | **新增** |
| `skin` | 主题 id（pink / hydrangea） | `eden_skin`（本期改为账号分区） | **新增** |

明确**不纳入**同步（附理由，防止以后误加）：

| 数据 | 理由 |
| --- | --- |
| 每日/每周计数器（`counters_day/week`）、`last_push_date_*`、`morning_sent_date` 等 | 本机当日去重状态。同步会导致「另一台设备 / 重装后当天不再发币、不再问候」 |
| `daily_loop_prefs` 今日步进、每日弹窗标记 | 同上，本机当天状态 |
| 专注运行中计时器（`focus_prefs.state/end_at/remaining_ms`） | 跨设备同步会产生两个设备同时计时的冲突；重装后恢复一个「还在跑」的计时器也是错误状态。本期只同步任务清单，运行中状态仍只在本机持久化 |
| 世界状态的每日收益去重（`EconomyManager.tryDailyGrant`） | 依赖本机计数器，天然按设备去重，不参与同步 |

### 4.1 主题皮肤改为账号级（用户已确认）

现状：[SkinManager.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/ui/theme/SkinManager.kt#L21-L41) 直接用设备级 `eden_skin`，不区分账号，也不参与同步。

改法：

1. `EdenStorage.USER_SCOPED_PREFS` 增加 `"eden_skin"`（本文件已在 [USER_SCOPED_PREFS](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/core/storage/EdenStorage.kt#L38-L43) 清单里，加一项即可）。
2. `SkinManager` 读写改走 `EdenStorage.prefs(context, "eden_skin")`。
3. **存量兼容**：`KEY_MIGRATED` 已为 true 的设备不会再执行 `migrateLegacyToUserScope`，旧文件不会自动改名。因此读取时若账号分区没有 `skin_id`，回退读设备级旧 `eden_skin`，读到后写回账号分区；**不删除旧文件**（供其他账号与回滚兜底）。
4. **即时生效**：登录拉取到 `skin` 后主题可能变化，但 `BaseActivity` 只在 `onResume` 比较（同步回调晚于 `onResume`）。需新增 `BaseActivity.refreshSkinIfNeeded()`，在 [MainActivity.syncOnLogin 回调](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/feature/home/MainActivity.kt#L231-L238) 里调用。
5. **行为变更**：主题从「设备共享」变为「账号独立」，换账号主题跟着换。这是本次确认后的预期行为，需在验收时覆盖。

---

## 五、数据流与状态

### 5.1 本地改动（离线也安全）

```
业务写本地 → notifyChanged(key)
   ├─ 写 sync_meta_prefs: <key>__updated_at = now
   ├─ 写 sync_meta_prefs: pending_keys += key（落盘）
   └─ 已登录 ? 启动防抖 worker : 到此为止（下次登录补传）
```

### 5.2 登录 / 启动

```
syncOnLogin
   ├─ pullSyncData
   │    ├─ 失败 → syncedToken=null，保留本地与队列，下次进入重试（现状）
   │    └─ 成功 → 逐条 importDoc(LWW)
   │         └─ 本地较新 → 记为待回传
   └─ backfill：枚举本地有意义的 key，减去远端已有 key → 入队
        └─ flush()
```

**「本地有意义」的判定**（避免给新账号写一堆空文档）：

- 静态 key：`<key>__updated_at > 0`，即本地确实改过；
- `avatar`：头像文件存在且长度 > 0；
- `chat:*`：该角色在 Room 中有记录（`SELECT DISTINCT characterId FROM chat_message`）。

### 5.3 上传

```
flush()（Mutex 串行）
   ├─ 取 pending_keys，排除 blocked_keys
   ├─ 逐 key exportDoc → 组装 docs（带本地 updated_at）
   ├─ pushSyncData
   │    ├─ 成功 → pending_keys -= 已发送 key
   │    └─ 失败 → 见 §七 失败语义表
   └─ 仍有剩余 → 交 WorkManager（网络约束）兜底
```

### 5.4 单个 key 的状态机

| 状态 | 进入条件 | 落盘字段 | 出口 |
| --- | --- | --- | --- |
| `Clean` | 本地与远端一致 | 无 pending | 任一次本地改动 → `Dirty` |
| `Dirty` | `notifyChanged` 且已登录 / 未登录 | `pending_keys` 含该 key | 上传成功 → `Clean` |
| `InFlight` | `flush()` 正在发送 | 不变（不删队列） | 成功 → `Clean`；失败 → `Dirty` |
| `Blocked` | 400 体积/格式错误且单 key 重试仍失败；或 401/403 | `blocked_keys` 含该 key | 用户重新编辑该数据（`notifyChanged` 清除 blocked）或重新登录 |

进程被杀时：`pending_keys` 已在磁盘，重启后由 WorkManager 或下次 `syncOnLogin` 继续；`InFlight` 无残留（幂等重发安全）。

---

## 六、头像同步设计

- **文档**：`avatar`，JSON `{"v":1,"mime":"image/jpeg","data":"<base64>"}`，便于以后换格式。
- **编码**（`ProfileRepository.exportAvatarJson`）：
  1. 读 `avatarFile(context)`；不存在返回 null（不产生文档）。
  2. `BitmapFactory` 带 `inSampleSize` 解码，长边压到 ≤ 1024 px。
  3. JPEG quality 90 → Base64（`NO_WRAP`）。
  4. 若 JSON 长度 > 1_800_000（后端单条上限 2_000_000，留余量），依次降级：quality 80（仍 1024）→ 长边 768 + quality 85；仍超限则**不静默成功**：抛带 key 与长度的异常，由上传层把该 key 记入 `blocked_keys` 并打日志。
- **解码**（`ProfileRepository.importAvatarJson`）：Base64 → 临时文件 → 原子改名到 `avatarFile(context)`；失败只影响头像，不阻断其他 key 的导入。
- **触发上传**：[ProfileRepository.saveAvatar()](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/data/repository/ProfileRepository.kt#L197-L201) 成功后调用 `SessionManager.notifyChanged(context, SyncKeys.AVATAR)`（现在没有这一句，这是头像永远不上传的直接原因）。
- **时间戳**：沿用 `sync_meta_prefs` 的 `avatar__updated_at`，走现有 LWW。
- **不做头像删除同步**：当前没有「删除头像」入口，不预设。
- **不写进 `settings` 文档**：避免每次改开关都重传几十 KB 图片。

---

## 七、失败语义表

约定：
- **幂等键**：`(user_id, data_key)`；上传是 LWW upsert，同 key 同 `updated_at` 重复发送结果一致，因此**允许重发**。
- 「谁负责恢复」：`自动` = 无需用户操作；`用户` = 需重新登录/重新编辑；`开发` = 查日志定位。

| # | 情况 | 行为 | 幂等键 | 谁负责恢复 |
| --- | --- | --- | --- | --- |
| 1 | 未登录时产生本地改动 | 只写时间戳 + 本地数据，不入队、不调度 | 无 | 自动：下次登录 `backfill` |
| 2 | 已登录、后端未启动 / 网络不通 | 入队并落盘；进程内重试 2 次（3s、6s）后交给 WorkManager（30s 起指数退避） | key | 自动 |
| 3 | 上传成功但响应丢失（结果未知） | 按失败处理，保留队列，下次重发；因是幂等 upsert，不会产生重复数据 | `(user_id,key,updated_at)` | 自动 |
| 4 | 进程在上传前/中被杀 | `pending_keys` 已落盘；WorkManager 或下次启动继续 | key | 自动 |
| 5 | HTTP 401 / 403 | 不重试、不删队列；取消 WorkManager，标记需重新登录；保留该账号分区数据 | key | 用户：重新登录后 `syncOnLogin` 继续 |
| 6 | HTTP 400（单条超长 / 格式错） | 先整批失败 → 拆成单 key 逐条重试一次；仍失败的 key 进 `blocked_keys`，其余正常上传 | key | 开发：看日志；用户重新编辑该数据可解除 |
| 7 | HTTP 408 / 429 / 5xx | 计为可重试；进程内 2 次后交 WorkManager | key | 自动 |
| 8 | 拉取失败（`pullSyncData` 非 0 / 异常） | 不改本地数据，`syncedToken=null`，本次不 backfill；队列上传不受影响 | — | 自动：下次进主界面重试 |
| 9 | 远端 key 不存在（新账号 / 首次上传） | 用 `backfill` 补传本地有意义的数据 | key | 自动 |
| 10 | 远端比本地新 | 覆盖本地，时间戳取远端 | key | 自动 |
| 11 | 本地比远端新 | 保留本地并回传 | key | 自动 |
| 12 | 头像超 2MB 上限 | 按 §六 降级；仍超限 → `blocked_keys` + 错误日志，**不假装成功** | `avatar` | 开发/用户重设头像 |
| 13 | 退出登录时有未上传 key | 队列留在该账号分区，不删除；取消 WorkManager；不清本地数据 | key | 自动：该账号下次登录 |
| 14 | WorkManager 重试耗尽 | 第 8 次失败后 `Result.failure`，队列保留；下次进前台 / 下次改动 / 下次登录重新调度 | key | 自动（下次触发） |
| 15 | 设备时钟回拨或与后端偏差大 | LWW 可能误判，属已知限制；本期不加服务端时间戳 | key | 开发（§八 记录） |

**重试参数（写进代码常量并注明理由）**：

| 常量 | 值 | 理由 |
| --- | --- | --- |
| `DEBOUNCE_MS` | 1500（沿用） | 合并短时间连续改动，减少请求数 |
| `IN_PROCESS_MAX_ATTEMPTS` | 3（首次 + 2 次重试） | 覆盖瞬时抖动，又不在前台长期空转 |
| `IN_PROCESS_BACKOFF_MS` | 3000 / 6000 | 沿用现有 3s 量级，指数增长 |
| `SYNC_WORK_INITIAL_BACKOFF` | 30s，`EXPONENTIAL` | 后端未启动时快速恢复；指数退避避免长期耗电 |
| `SYNC_WORK_MAX_ATTEMPTS` | 8 | 约覆盖 30s→5h 的退避跨度；超过后交下次前台触发，避免无限后台唤醒 |

---

## 八、冲突、边界与已知风险

1. **时钟**：LWW 用客户端时间。设备时间被手动改晚会覆盖较新数据。本期只记录风险，不引入服务端时间戳（会改动后端契约）。
2. **多设备**：用户确认「同账号只在一台设备登录」为使用约定，代码不强制。因此仍按同一 key 后写覆盖先写处理；任务清单、头像、世界状态等是整文档覆盖，不做条目级合并。若实际出现两设备并发，可能互相覆盖，属已知限制。
3. **`focus_tasks`**：只同步任务标题 / 目标番茄数 / 已完成数 / 归档状态；**不同步运行中计时器**（重装后恢复「还在跑」的计时器是错误状态）。两端同时改任务时整表 LWW。
4. **`world` / `story`**：按「一个文档装所有角色/线路」实现，避免 key 数量爆炸；单卡带量很小，远低于 2MB。若未来剧情文本入库再拆。
5. **旧版本兼容**：老版本 App 不认识的 key 不会拉取，也不会因此报错；新版本写入的新 key 对老版本只是不可见。回滚 = 回退 APK，服务端多出的文档无副作用。
6. **账号分区**：新 key 的时间戳与队列都在 `sync_meta_prefs`（已在 [USER_SCOPED_PREFS](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/core/storage/EdenStorage.kt#L38-L43) 清单内），切号天然隔离。
7. **主题迁移**：存量设备 `KEY_MIGRATED=true`，旧 `eden_skin.xml` 不会被自动改名，必须靠 §4.1 的读取回退；若回退逻辑写错，表现为「升级后主题回到粉色」，不丢其他数据。
8. **回滚**：全部是客户端改动 + 服务端多出 `avatar/story/milestone/world/focus_tasks/skin` 文档；回退 APK 后这些文档被忽略，无需清理。主题回滚后新版本写入的账号级 `eden_skin_u<id>` 会被旧版本忽略，设备级旧文件仍在，行为回到改造前。

---

## 九、验收标准与测试计划

### 验收标准（每条都可复现）

1. 关掉后端 → 改昵称 / 花金币 / 换头像 / 推进剧情 → 杀进程 → 重开：改动仍在。
2. 启动后端 → 不做任何操作 → 日志出现该 key 的上传成功，服务端 `user_data` 出现对应行。
3. 卸载重装 → 启动后端 → 同账号登录：头像、金币、昵称、剧情进度、里程碑、世界状态、专注任务全部恢复。
4. 新账号（服务端 0 文档）首次登录：不会写入空 `diary` / 空 `wallet` 等无意义文档。
5. 头像按长边 1024 / q90 压缩后单条文档 < 2MB；超限路径有明确错误日志且不阻塞其他 key。
6. 退出登录再登回：未上传队列仍在，并继续上传。
7. 主题：账号 A 选 hydrangea → 切到账号 B 仍是 B 自己的主题 → 切回 A 恢复 hydrangea；重装后登录 A 自动恢复 hydrangea。
8. 存量设备升级（`KEY_MIGRATED=true`、旧 `eden_skin.xml` 存在）：升级后主题不重置。

### 测试计划（**尚未执行**）

| 测试 | 类型 | 覆盖 |
| --- | --- | --- |
| `SyncDecisionTest` | JVM 单测 | LWW 判定（远端新 / 本地新 / 相等 / 时间戳 0） |
| `PendingQueueTest` | JVM 单测 | 队列持久化读写的增删、去重、blocked 过滤 |
| `AvatarCodecTest` | androidTest（依赖 `Bitmap`） | 压缩后尺寸/大小上限、base64 往返、超限抛错 |
| `SyncKeysTest` | JVM 单测 | 新 key 的 `SyncKeys` 常量与 `chat:` 生成 |
| 回归 | 手工 | 上述 8 条验收标准 |

计划执行的命令（**均未执行**）：

```
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:connectedDebugAndroidTest
.\gradlew.bat :app:assembleDebug
```

> 本文不声称任何测试已通过。实施完成后会回填真实命令、输出摘要、测试名与数量。

---

## 十、实施落点预览（详细改动清单在实施文档中给出）

| 文件 | 预期改动 |
| --- | --- |
| [SyncModels.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/core/session/SyncModels.kt#L36-L50) | 新增 6 个 key 常量（avatar / story / milestone / world / focus_tasks / skin） |
| [SessionManager.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/core/session/SessionManager.kt) | 持久队列、backfill、flush、阻塞集合、锁；`exportDoc/applyRemoteDoc` 增加分支 |
| [ProfileRepository.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/data/repository/ProfileRepository.kt) | 头像导出/导入；`saveAvatar` 通知变更 |
| `StoryProgressRepository.kt` / `MilestoneRepository.kt` / `WorldStateStore.kt` | 增加 `exportJson/importJson` |
| `FocusRepository.kt` | 任务清单导出/导入 |
| [ChatRepository.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/data/repository/ChatRepository.kt) / [ChatMessageDao.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/data/local/ChatMessageDao.kt) | 枚举有记录的角色 id |
| `SyncWorker.kt`（新增） | WorkManager 兜底 |
| [EdenStorage.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/core/storage/EdenStorage.kt#L38-L43) | `USER_SCOPED_PREFS` 增加 `eden_skin` |
| [SkinManager.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/ui/theme/SkinManager.kt) | 改走账号分区 + 旧设备级文件回退读取 |
| [BaseActivity.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/core/ui/BaseActivity.kt) | 新增 `refreshSkinIfNeeded()` |
| [MainActivity.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/feature/home/MainActivity.kt#L231-L238) | 同步完成回调里刷新头像/主题相关展示（如已变更） |

---

## 十一、已确认结论与开工条件

| 事项 | 结论 |
| --- | --- |
| 主题皮肤 | 改为账号级并同步（含存量兼容读取、同步后 `recreate`） |
| 专注任务 | 只同步任务清单，不同步运行中计时器 |
| 头像 | 长边 1024 / q90，单张，不做删除同步 |
| 同账号多设备 | 仅作为使用约定，代码不强制；冲突仍走 LWW |

开工条件：以上四项已确认；本文的失败语义表、重试参数、本期不做清单、复用/禁止重写清单齐全。**评审通过后开始改代码**，实施完成时回填真实命令、输出摘要、测试名与数量。
