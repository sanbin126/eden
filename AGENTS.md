# Repository Guidelines

本文件约束参与 Eden 项目的 AI 与人类协作者。优先级：数据安全 > 事实正确 > 用户请求 > 本文默认规范。

## Project Structure & Module Organization

Eden 是 Android 客户端 + FastAPI 后端的单仓库项目。

- Android 工程只有一个模块 `:app`，namespace 为 `com.example.eden`，applicationId 为 `com.wanderer.eden`；`minSdk 24`，`compileSdk/targetSdk 36`，Java 11 字节码，Kotlin 2.0.21 + AGP 8.13.1。
- 客户端代码在 `app/src/main/java/com/example/eden`：
  - `core/network`：Retrofit/OkHttp、LLM 客户端与双模式判定、重试拦截器。
  - `core/session`：登录态、同步模型与 `SessionManager`（同步唯一编排点）。
  - `core/storage`：`EdenStorage` 账号分区存储入口。
  - `data/local`、`data/repository`：Room 实体/DAO 与仓库。
  - `domain`：角色人设、剧情、经济、记忆、世界状态等纯业务模型。
  - `feature`：按功能划分的 Activity/View/Controller。
  - `ui/theme`：主题、皮肤与配色。
- 资源：布局在 `app/src/main/res/layout`，图片在 `drawable*`/`mipmap*`；剧情 JSON 在 `app/src/main/assets/story/miku`，立绘 `assets/portrait`，背景 `assets/story_bg`，CG `assets/story_cg`，小游戏资源 `assets/platformer`。
- 后端唯一实现是 `backend/app/main.py`（单文件 FastAPI + SQLite），部署文件为 `backend/Dockerfile` 与 `backend/requirements.txt`。历史文档与注释中的 `server/` 是旧路径，不要新建 `server/` 副本。
- 设计/实施文档在 `docs/` 与仓库根目录；素材源文件在 `art_source/`；Kenney 素材包与 `eden-introduction/` 为参考资料，不属于运行时代码。
- 测试目前只有 `app/src/test/java/com/example/eden/ExampleUnitTest.kt`；后端尚无测试目录与测试框架。

## Change Workflow

- 开始前先确认当前分支、远程与未提交改动；不得覆盖用户未提交的工作。
- 先读后改：回复中列出将要修改的文件路径 + 函数名/行号；只读与本次改动相关的文件，禁止全量重读仓库。
- 需求有歧义时先列出合理解释并询问；不要自行扩大范围。
- 只实现明确请求，优先最简单做法；不顺手重构、不新增推测性抽象或开关。
- 按“确认现状 → 精准修改 → 针对性验证”执行；每处改动都应能追溯到用户请求。
- 会改变既有行为或影响其他模块时，先说明影响面再动手。
- 提交、推送、打包发布、部署、改线上配置、删数据，均须用户明确指示；默认只改本地代码。

## Design Documents

修改以下模块前，先只读对应文档中与本次改动相关的章节，不整篇通读：

| Document | Covers |
|----------|--------|
| `docs/offline-sync-design.md` | 本地优先同步：同步 key 注册表、LWW、待传/阻塞队列、补传、失败语义与验收标准 |
| `docs/daily-loop-system.md` | 每日循环的任务、计数与去重状态 |
| `docs/anniversary-system.md` | 纪念日/生日数据与提醒 |
| `docs/chat-experience-optimization.md` | 聊天体验、消息渲染与交互优化 |
| `docs/music-bgm-feature.md` | 背景音乐、环境音与前台 Service |
| `docs/platformer-game.md` | 平台跳跃小游戏（含第三阶段失败语义表与不变量） |
| `双模式LLM调用实施文档.md` | 后端/直连双模式、密钥注入边界、回滚方案 |
| `世界状态系统实施文档.md` | WorldState 五条不变量、计算规则、存储、事件与 prompt 注入 |
| `角色回答真实性优化实施文档.md` | 人设提示词、温度、联网搜索、运行模式与回归用例 |
| `账号注销设计方案.md` | 账号注销接口与状态机；仅为设计，当前未实现 |
| `CloudBase部署实施文档.md` | 后端云托管构建、环境变量与控制台参数 |
| `Android上架实施清单.md` | 签名、包名、权限、隐私、版本号与提审自检 |

拟改变文档规定的核心约定、状态机或同步 key 语义时，先向用户说明变更内容并取得确认。

## Module Invariants

### 本地存储与离线同步

- 同步相关改动以 `docs/offline-sync-design.md` 为准；`SyncModels.kt` 顶部“真实数据以后端为准”的旧注释与设计冲突，不得作为实现依据。
- 所有账号级 `SharedPreferences` 必须经 `EdenStorage.prefs(context, base)` 读取，并把 base 名加入 `EdenStorage.USER_SCOPED_PREFS`；禁止在业务代码里直接 `getSharedPreferences("xxx")` 新增存储。
- 新账号业务数据必须同时满足：有稳定同步 key（`SyncKeys`）、有 `exportDoc/importDoc`、失败可恢复；否则先补设计。
- `SessionManager` 是唯一同步编排点；禁止另建同步队列、同步服务或第二套 LWW。
- `importDoc` 路径不得回调 `notifyChanged`，否则形成上传回环。
- 待上传与阻塞 key 必须落盘；禁止用 catch-all 静默吞掉上传失败。
- 超时与 5xx 视为 `outcome_unknown`：只反查原任务，不自动重建、不重复提交写操作。401/403 进入阻塞态，等待重新登录；400 单 key 重试后仍失败才阻塞。每次重试须写明次数、退避与失败落点。
- `RetryInterceptor` 只对 408/429/5xx/IOException 重试，最多 3 次指数退避 + 抖动；不得扩大到 4xx 或无限重试。
- 本机当日去重状态（每日/每周计数器、`last_push_date_*`、问候标记）、专注运行中计时器不纳入同步；不要“顺手”加进去。

### LLM 与密钥

- release 包永远走 `LlmMode.BACKEND`；直连模式只允许 debug 使用，且 Key 只从 `local.properties` / 环境变量读取，禁止写进源码或提交仓库。
- 客户端业务代码统一通过 `LlmClient` / `LlmModeManager` 发起 AI 请求，不在 feature 层直接拼 OkHttp 调用。
- 大模型、SMTP、搜索服务密钥只存在于后端环境变量；日志只允许输出掩码后的值。
- 后端响应保持统一信封 `{"code", "message", "data"}`，业务失败用 HTTP 200 + `code != 0`；只有登录态失效等鉴权场景用 401。字段名（含 camelCase）必须与客户端模型一致，改字段等于改协议。
- 联网搜索未配置 `SEARCH_API_KEY` 时行为必须与未接入前完全一致；搜索失败不得拖垮整轮对话。

### 世界状态、人设与经济

- `世界状态系统实施文档.md` 的五条不变量是实现红线；不得随意改公式、事件语义或 prompt 注入顺序。
- 人设提示词影响全产品语气；修改后必须按 `角色回答真实性优化实施文档.md` §6.4 做人设回归，且不得把人设写成事实知识库。
- 金币发放/扣减统一走 `EconomyManager`；每日收益去重依赖本机计数器，禁止改成跨设备同步逻辑。
- 剧情 JSON 与资源路径被 `StoryLibrary`/剧情播放器引用；改结构或改文件名前先确认所有引用方。

### Android 发布

- `versionCode` 每次提审必须 +1 且永不复用；`versionName` 同步更新。
- release 签名只从 `local.properties` 的 `EDEN_RELEASE_STORE_FILE/PASSWORD/KEY_ALIAS/KEY_PASSWORD` 读取；缺失时构建应失败，不得产出未签名包。密钥库与密码不入库。
- release 接口地址只从 `EDEN_RELEASE_AUTH_BASE_URL` / `EDEN_RELEASE_AI_BASE_URL` 读取；正式包禁止明文 HTTP，明文放行只允许存在于 `app/src/debug`。
- 隐私同意必须在任何权限申请、网络请求、本地业务初始化之前完成；权限只保留实际使用的项。
- 正式包不得输出包含聊天内容、城市、账号、验证码的日志；验证码明文日志属于上线前必须移除项。

## Context Budget

- 定位优先：先 Grep/Glob 找文件与行号，再用 `offset`/`limit` 读取；超过 400 行的文件不做无条件全量读取（如 `MainActivity.kt`、`MikuPersona.kt`、`StoryPlayerActivity.kt`）。
- 设计文档按章节读，只读与当前模块相关的部分。
- 子代理只回结论、证据摘要与关键行号，不回传完整文件。
- 跨多轮任务把已验证结论、已排除假设和待办写入临时文件（不纳入 Git），压缩后从文件恢复。
- 日志、测试与构建输出只保留失败用例、错误摘要和统计行。
- 单任务工具调用超过 10 次或上下文超过 50K，停下汇报进度与下一步，不自动续跑。

## Specs, Evidence, and Verification

- 跨模块，或触及状态机、异步任务、数据迁移、同步协议、计费/经济的改动，先写设计文档。五要素：业务目标、职责与依赖、数据流与状态、失败语义表（情况/行为/幂等键/谁恢复）、本期不做。
- 缺失败语义表视为未完成，不进入实现；用户坚持先写代码时，先列出缺失章节再执行。
- 只列文件落点与改动清单的实施说明不能替代设计文档。
- 完成必须给证据：执行的命令、输出首尾与统计行、覆盖改动的测试名与数量。“已静态复核”“应该可以”“已实现”不算完成。
- 无法运行时写“未验证 + 原因”，不得改写成肯定句。
- 新旧逻辑共存必须做隔离验证：开关关闭/旧路径下输出与改动前一致；只给新路径单测不算隔离证明。
- 不确定的失败不得自动重投；每次重试写明最大次数、退避和失败落点。

## Build, Test, and Development Commands

Android（需 JDK 17/21；JBR Java 25 不被 Gradle 8.13 支持）：

```powershell
$env:JAVA_HOME="C:\Users\suwei\.jdks\jbr-21.0.11"
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:assembleRelease   # 需 local.properties 四项签名配置，否则构建失败
.\gradlew.bat signingReport
```

后端本地联调：

```powershell
cd backend
pip install -r requirements.txt
python -m py_compile app/main.py
uvicorn app.main:app --host 0.0.0.0 --port 8001
```

客户端默认 `AI_BASE_URL` 为 `http://10.0.2.2:8001/`，后端默认 `PORT=80`；本地联调必须把两者对齐（启动时指定端口，或配置 `EDEN_AI_BASE_URL`）。容器与线上禁止 `--reload`，容器内固定监听 80。

## Coding Style & Naming Conventions

- Kotlin 使用官方风格与 4 空格缩进；包名全小写，类/Activity 用 PascalCase，函数/变量用 camelCase，常量用 UPPER_SNAKE_CASE。
- Activity 以 `XxxActivity` 命名并放在对应 `feature/<domain>`；布局用 `activity_*`、`dialog_*`、`item_*`、`view_*`；drawable 用 `bg_*`、`ic_*`。
- 新增用户可见文案优先放入 `res/values/strings.xml`；不批量迁移已有硬编码文案（避免混入无关重构）。
- 不新增 Java 源文件；沿用 Kotlin，保持 Java 11 目标。
- 注释用中文，只解释“为什么”，不复述代码。
- 后端保持 Python 3.12，优先标准库；新增第三方依赖须说明用途并同步 `requirements.txt`。配置只从环境变量或 `backend/app/.env` 读取，响应字段名保持稳定。
- 资源/素材不重命名已被代码引用的路径；新增素材放入既有目录规则（见 Project Structure）。

## Testing Guidelines

- 客户端单测用 JUnit4，放在 `app/src/test/java/...`；新增行为优先在仓库/领域层做可单测的纯函数测试。
- 同步类改动至少覆盖：两个账号切换不串号、后端停机时本地可用、恢复后补传、401 阻塞与重新登录恢复。
- UI 改动需 `assembleDebug` 通过并说明真机/模拟器验证步骤；视觉类附截图或明确“未验证”。
- 后端目前无自动化测试；改动后用 `py_compile` + 实际请求 `/health`、`/api/chat`、`/api/v1/auth/*` 验证，并在回复中给出请求与响应摘要。不得声称跑过不存在的测试。

## Commit & Pull Request Guidelines

- 提交信息用简短中文，沿用现有风格：`feat: ...`、`fix: ...`、`chore: ...`；一次提交只做一件事。
- 提交前用 `git status` 确认；不提交 `local.properties`、`.env`、`*.jks`、数据库、`.jwt_secret`、构建产物与 APK。
- 仓库远程为 Gitee（`wanderder0/eden`）；未经明确指示不 commit、不 push。
- PR/MR 描述包含：改动摘要、关联需求、验证命令与结果、迁移/兼容说明；UI 改动附截图。

## Security & Configuration Tips

- 本地配置：Android 用 `local.properties`；后端复制 `backend/.env.example` 为 `backend/app/.env`（该示例文件含覆盖警告，禁止整文件覆盖已有 `.env`）。
- 禁止把密钥、token、cookie、连接串写进代码、日志、提交信息或文档。
- 后端 SQLite 当前为单文件、进程内连接 + 全局锁；不要假设多实例安全。多实例部署必须显式配置同一个 `JWT_SECRET`，并解决数据库共享问题。
- 账号注销目前只有设计文档、没有实现；不要在未实现的情况下对外声称支持注销。
