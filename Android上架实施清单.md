# Eden Android 上架实施清单（个人开发者 / 国内商店）

> 目标：把当前工程整理成**可提交国内应用商店**的正式包，并明确哪些事我能直接改代码、哪些必须你本人去办。
>
> 适用主体：**个人开发者**
> 目标渠道：**国内应用商店（华为 / 小米 / OPPO / vivo / 荣耀 / 应用宝）**
>
> 说明：所有商店与备案规则会随年份调整，本文给的是流程骨架和本工程的确定性问题；具体材料与门槛以你提交时对应平台的最新要求为准。

---

## 0. 当前工程现状（已逐项核对代码）

| 项目 | 现状 | 位置 |
|---|---|---|
| 签名 | **无 keystore、无 signingConfig**，只能出 debug 包 | [build.gradle.kts](file:///c:/Users/suwei/Desktop/eden/app/build.gradle.kts#L74-L82) |
| 包名 | `com.example.eden`（`com.example` 是官方保留前缀，商店拒收） | [build.gradle.kts](file:///c:/Users/suwei/Desktop/eden/app/build.gradle.kts#L29) |
| 版本号 | `versionCode = 1`、`versionName = "1.0"` | [build.gradle.kts](file:///c:/Users/suwei/Desktop/eden/app/build.gradle.kts#L32-L33) |
| 明文流量 | `usesCleartextTraffic="true"`（审核会挑） | [AndroidManifest.xml](file:///c:/Users/suwei/Desktop/eden/app/src/main/AndroidManifest.xml#L37) |
| 权限 | 声明了 11 个权限，其中 5 个实际未使用（见 1.4） | [AndroidManifest.xml](file:///c:/Users/suwei/Desktop/eden/app/src/main/AndroidManifest.xml#L5-L24) |
| 定位 | `ACCESS_FINE_LOCATION`，用于天气 | [WeatherRepository.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/feature/weather/WeatherRepository.kt#L194-L228) |
| 隐私弹窗 | **无**；启动即在 `Application.onCreate` 做本地初始化 | [EdenApp.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/EdenApp.kt#L28-L37) |
| 账号注销 | **无任何注销 / 删除账号入口**（全项目搜索无匹配），商店必卡 | — |
| 日志 | 8 个文件共 42 处 `Log.*`，含城市名、聊天内容 | 分布在 weather / music / chat 等 |
| 64 位 | 纯 Kotlin/Java，无 native so，天然满足 | — |
| 后端 | FastAPI + SQLite，目前用 CloudBase 测试域名，**无法用于备案** | [backend/Dockerfile](file:///c:/Users/suwei/Desktop/eden/backend/Dockerfile) |

---

## 1. 我直接改的部分（代码与工程配置）

以下每一项都是我能落地并自测的，按依赖顺序排列。**第 1.1 必须由你先产出密钥库**，否则签名配置没有文件可指向。

### 1.1 签名配置（需要你先给密钥库，我接进去）

你按下面命令生成一次，之后**永远只备份不重建**：

```powershell
cd c:\Users\suwei\Desktop\eden
keytool -genkeypair -v -keystore eden-release.jks `
  -alias eden -keyalg RSA -keysize 2048 -validity 36500
```

- `keytool` 在 Android Studio 自带 JDK 里，若提示找不到，用 `& "$env:JAVA_HOME\bin\keytool.exe"` 调起。
- 过程中会问你 **keystore 密码 / key 密码 / 姓名组织等信息**。这两个密码由你设定并记进密码管理器，**不要写进任何代码或文档**。
- 生成后请把 `eden-release.jks` 放到工程根目录，然后在 `local.properties` 追加（该文件已在 [.gitignore](file:///c:/Users/suwei/Desktop/eden/.gitignore#L3-L15) 中忽略）：

```properties
EDEN_RELEASE_STORE_FILE=eden-release.jks
EDEN_RELEASE_STORE_PASSWORD=你的keystore密码
EDEN_RELEASE_KEY_ALIAS=eden
EDEN_RELEASE_KEY_PASSWORD=你的key密码
```

我随后会做：

- `build.gradle.kts` 新增 `signingConfigs.release`，从 `local.properties` 读取，**缺失时构建直接失败并给出明确提示**（避免误打出未签名包）
- `buildTypes.release` 绑定该签名配置
- `.gitignore` 增加 `*.jks`、`*.keystore`，防止密钥库被误提交
- 验证：`.\gradlew.bat signingReport` 能看到 release 证书指纹

### 1.2 修改 applicationId

只改 `applicationId`，**不移动源码目录**。原因是 `namespace` 只决定 R 类包名，属于编译期标识；`applicationId` 才是设备上的唯一标识和商店识别依据。

- `com.example.eden` → 你确认的新包名（默认建议 `com.suwei.eden`）
- 同步检查代码里是否有硬编码的包名字符串（如 `"com.example.eden"` 用于 Intent / 权限），有则一起改
- 验收：安装后 `adb shell pm list packages | findstr eden` 显示新包名

> 注意：包名一旦上架不可更改，改包名等于换一个 App，用户数据不迁移。必须在首次提审前定下来。

### 1.3 关闭明文流量

- 新增 `res/xml/network_security_config.xml`：release 禁止明文，仅保留系统 CA
- `AndroidManifest.xml` 移除 `usesCleartextTraffic="true"`，改为引用该配置
- 本地开发（`10.0.2.2`、局域网 IP）的 HTTP 需求，通过 `src/debug/AndroidManifest.xml` 单独放行，**不影响 release 包**
- 验收：release 包内 `aapt dump xmltree` 无 `usesCleartextTraffic=true`；真机用 HTTP 地址请求时抛 `CleartextNotPermitted`

### 1.4 权限收敛

| 权限 | 处理 | 依据 |
|---|---|---|
| `INTERNET` | 保留 | 必需 |
| `POST_NOTIFICATIONS` | 保留 | 问候通知 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | 保留 | [MusicService](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/feature/music/MusicService.kt) |
| `SCHEDULE_EXACT_ALARM` / `RECEIVE_BOOT_COMPLETED` | 保留，提审时说明用途为"每日问候提醒" | [GreetingScheduler](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/feature/greeting/GreetingScheduler.kt) |
| `USE_EXACT_ALARM` | 视商店要求决定是否移除（该权限在 Google Play 仅限闹钟/日历类，国内商店口径不一） | — |
| `ACCESS_FINE_LOCATION` | 降级为 `ACCESS_COARSE_LOCATION`，代码同步改 | 天气只需城市级 |
| `READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE` | **删除**，同步删掉 [MainActivity.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/feature/home/MainActivity.kt#L574-L599) 里的权限申请分支 | 本地音乐走的是 SAF `OpenDocument`（[MainActivity.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/feature/home/MainActivity.kt#L183-L185)），不需要该权限 |
| `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` | **删除** | 头像走的是系统相册选择器 `PickVisualMedia`（[ProfileEditActivity.kt](file:///c:/Users/suwei/Desktop/eden/app/src/main/java/com/example/eden/feature/profile/ProfileEditActivity.kt#L36-L76)），不需要该权限 |
| `ACCESS_NETWORK_STATE` | **删除** | 全项目无 `ConnectivityManager` 引用 |

- 验收：`aapt dump permissions` 输出仅剩白名单；安装后系统设置里的权限列表与隐私政策一致

### 1.5 隐私政策与同意弹窗

- 新增 `PrivacyConsentActivity` 作为 `LAUNCHER` 入口：首次启动展示隐私政策摘要（含跳转完整政策链接），**用户点"同意"前不申请任何权限、不发任何网络请求**；点"不同意"则退出应用
- 同意状态存入本地 prefs，之后直接进入现有流程
- 现有权限申请点（聊天页定位、通知、选歌）全部改为在同意之后才可能触发
- 政策正文里需要填的第三方与数据项：邮箱、聊天记录、长期记忆、位置（城市级）、图片（头像）、天气接口 open-meteo、大模型服务、SMTP 邮件服务
- **依赖你的产出**：隐私政策的公网 URL（见 2.3），代码里先留占位并在同意前可点开

### 1.6 账号注销入口（跨端改动，需先出设计）

这是商店硬性要求，但涉及客户端 + 后端 + 数据删除，按项目规则我会**先给一份含失败语义表的设计**（删除范围、幂等键、令牌失效后的处理、部分删除失败如何恢复），确认后再实现。不会直接开写。

### 1.7 版本号规范与日志收口

- `versionCode` 改起始值并约定规则（每次提审 +1，绝不复用）
- `versionName` 改为语义化版本，如 `1.0.0`
- 42 处 `Log.*`：release 包统一关闭（通过 buildConfig 开关或改 `BuildConfig.DEBUG` 判断），保留必要错误日志但不含聊天内容、城市、邮箱

### 1.8 出包与自测

- `.\gradlew.bat clean assembleRelease`
- 产物：`app/build/outputs/apk/release/app-release.apk`
- 自测项：全新安装 → 隐私弹窗 → 拒绝一次 → 重启再同意 → 注册登录 → 聊天 → 天气 → 通知 → 后台播放 → 卸载重装数据行为
- 我会给出实际执行的命令与输出摘要，不会用"应该可以"结题

---

## 2. 必须你本人操作的部分

以下都需要你的身份、你的账号或你的钱，我无法代办。

### 2.1 生成并保管签名密钥（最优先，10 分钟）

见 1.1 的命令。三个硬要求：

1. **keystore 文件 + 两个密码 + alias 三样一起备份**（网盘 + 本地各一份）
2. 丢失后**无法发布更新**，只能换包名重新上架，老用户全部丢失
3. 不要发给任何人，不要提交 git

### 2.2 软件著作权登记（最慢，先启动）

- 入口：中国版权保护中心「软件著作权登记系统」
- 主体：个人，需身份证
- 材料：源程序前后各 30 页（共 60 页，页眉需含软件名称与版本号）+ 软件说明书（功能、运行环境、操作流程）
- 官方登记不收费；加急通道需付费给代理
- 产出：**软著登记证书**，几乎所有国内商店都要
- 注意：软件名称建议与 App 名称一致，版本号与 `versionName` 对应

### 2.3 域名 + ICP 备案 + APP 备案

这是当前最大的外部依赖，因为后端现在用的是 CloudBase 测试域名，**不是你的域名，无法备案**。

1. 买域名（.com / .cn 均可）
2. 在境内云厂商买服务器（阿里云 / 腾讯云等），做 **ICP 备案**（个人主体可办，需要身份证、核验、网站负责人照片等）
3. ICP 通过后，在备案系统做 **APP 备案**：填 App 名称、包名、**签名证书指纹**、使用的域名与服务器
4. 备案通过后才把后端切到 `https://你的域名`

**关键顺序**：先定签名（2.1）→ 再备案（要填签名指纹）→ 再提审。中途换签名会导致备案信息与实际包不一致。

### 2.4 隐私政策页面

- 需要一个公网可访问的 HTTPS 页面（放在已备案域名下最省事）
- 必须写清：收集的信息种类与用途、存储位置与期限、第三方服务清单、权限用途逐条说明、用户权利（查询/更正/删除/注销）、联系方式
- 商店会实际点开检查，页面打不开直接驳回

### 2.5 注册开发者账号

- 华为、小米、OPPO、vivo、荣耀、应用宝：个人主体均可注册，需身份证实名 + 人脸核身
- 注册后按要求上传签名证书或使用平台代签名
- **个人主体在社交、内容、AI 类目可能被限制**，注册时先看清该类目对个人是否开放

### 2.6 提交审核

- 上传 1.8 产出的 release APK
- 填写：应用信息、截图、隐私政策链接、权限使用说明、测试账号（**必须提供可登录的测试账号**，否则审核无法进入主界面直接驳回）
- 建议**先只投一家**（华为或小米），拿到具体驳回理由再决定后续

### 2.7 后端正式部署

可选由我协助，但需要你先提供域名与服务器信息。部署到自有服务器时注意：

- `DB_PATH` 指向持久化卷，否则容器重建丢账号
- `JWT_SECRET` 显式配置且固定，否则重建后全员掉登录
- 只跑 1 个实例（SQLite 限制）
- `APP_TOKEN` 建议配置，否则 `/api/chat` 对公网完全开放，会被刷额度

---

## 3. 依赖关系与执行顺序

```
[你] 2.1 生成签名密钥
        │
        ├──> [我] 1.1 签名配置 ──┐
        │                        │
        ├──> [我] 1.2 改包名 ────┤
        │                        ├──> [我] 1.8 出正式包
        ├──> [我] 1.3 明文流量 ──┤
        │                        │
        ├──> [我] 1.4 权限收敛 ──┤
        │                        │
        ├──> [我] 1.5 隐私弹窗 ──┘（需 2.4 的 URL）
        │
        ├──> [我] 1.6 注销设计 → 确认 → 实现
        │
        └──> [你] 2.2 软著（并行，最慢）
             [你] 2.3 域名+ICP+APP备案（并行，依赖 2.1 的签名指纹）
             [你] 2.4 隐私政策页（并行）
             [你] 2.5 开发者账号（并行）
                        │
                        └──> [你] 2.6 提交审核
```

关键结论：**我能做的代码整改，最快一批可以独立完成；但上架时间由 2.2 软著和 2.3 备案决定，不是代码。**

---

## 4. 提审前自检清单

- [ ] release 包用正式签名，证书指纹已记录
- [ ] `applicationId` 不是 `com.example.*`
- [ ] 版本号规范，`versionCode` 未与历史重复
- [ ] release 包无 `usesCleartextTraffic=true`
- [ ] 权限列表只剩必需项，且与隐私政策逐条对应
- [ ] 首次启动有隐私弹窗，同意前无权限申请、无网络请求
- [ ] 有账号注销入口，且能真正删除服务端数据
- [ ] 隐私政策链接公网可打开
- [ ] 后端域名已备案，全站 HTTPS，接口无 IP 直连
- [ ] 提供可用的测试账号
- [ ] 软著证书已下证
- [ ] 应用截图、图标、简介与功能一致（不含未实现功能）
- [ ] 内容分级已填写，亲密互动相关内容已明确边界

---

## 5. 风险与不确定项（不粉饰）

1. **个人主体 + AI 陪聊类目通过率不确定**。技术上可提审，但类目资质是商店裁量权，个人主体被拒的概率明显高于企业。若被拒，最可能的理由是内容合规或类目资质。
2. **生成式 AI 服务面向公众提供，可能涉及额外备案要求**（算法/生成式 AI 相关）。这一条我无法替你判断是否适用，需要你按当地通信管理局/平台要求确认，本文不给结论。
3. **备案与商店规则逐年变化**，本文只给顺序骨架；具体材料、是否要求企业主体、targetSdk 门槛，均以提交时平台文档为准。
4. **软著审核周期不受你我控制**，是整体排期的关键路径。
5. **改包名后老数据不迁移**：如果你之前已经把 debug 包发给朋友用过，改包名后会被识别为另一个 App，本地数据不会带过去。
6. 1.6 账号注销涉及服务端数据删除，我只会先出设计；未确认前不会动后端删除逻辑。

---

## 6. 命令速查

```powershell
# 生成签名密钥（一次性）
keytool -genkeypair -v -keystore eden-release.jks -alias eden `
  -keyalg RSA -keysize 2048 -validity 36500

# 查看证书指纹（备案、商店需要）
keytool -list -v -keystore eden-release.jks -alias eden

# 出正式包
cd c:\Users\suwei\Desktop\eden
.\gradlew.bat clean assembleRelease

# 安装到已连设备
adb install -r app\build\outputs\apk\release\app-release.apk

# 核对包内权限与明文配置
aapt dump permissions app\build\outputs\apk\release\app-release.apk
```

---

## 7. 下一步

你现在只需做一件事：**执行 2.1 生成 `eden-release.jks` 并把四项配置填进 `local.properties`**，然后告诉我新包名。

我拿到后按 1.1 → 1.2 → 1.3 → 1.4 → 1.7 的顺序改完并出包，再单独提 1.6 的注销设计给你确认，最后接 1.5 隐私弹窗（等你的政策 URL）。
