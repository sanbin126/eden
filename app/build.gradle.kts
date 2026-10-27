import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
}

// 从 local.properties / 环境变量读取敏感配置，避免 API Key 硬编码进源码。
// local.properties 已在 .gitignore 中，不会随代码一起提交。
val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use { load(it) }
    }
}

fun secretOr(key: String, defaultValue: String): String =
    localProperties.getProperty(key) ?: System.getenv(key) ?: defaultValue

// ===== 正式包签名 =====
// 四项配置只写在 local.properties（已 gitignore）或同名的环境变量里。
// 任一缺失时 assembleRelease 直接失败并给出明确提示，避免误产出未签名/调试签名的包。
val releaseStoreFile = secretOr("EDEN_RELEASE_STORE_FILE", "")
val releaseStorePassword = secretOr("EDEN_RELEASE_STORE_PASSWORD", "")
val releaseKeyAlias = secretOr("EDEN_RELEASE_KEY_ALIAS", "")
val releaseKeyPassword = secretOr("EDEN_RELEASE_KEY_PASSWORD", "")

fun releaseSigningProblem(): String? = when {
    releaseStoreFile.isBlank() -> "EDEN_RELEASE_STORE_FILE 未配置"
    !rootProject.file(releaseStoreFile).exists() ->
        "签名文件不存在：${rootProject.file(releaseStoreFile).absolutePath}"
    releaseStorePassword.isBlank() -> "EDEN_RELEASE_STORE_PASSWORD 未配置"
    releaseKeyAlias.isBlank() -> "EDEN_RELEASE_KEY_ALIAS 未配置"
    releaseKeyPassword.isBlank() -> "EDEN_RELEASE_KEY_PASSWORD 未配置"
    else -> null
}

android {
    namespace = "com.example.eden"
    compileSdk {
        version = release(36)
    }

    signingConfigs {
        create("release") {
            if (releaseStoreFile.isNotBlank()) {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.wanderer.eden"
        minSdk = 24
        targetSdk = 36
        // 每次提审 versionCode 必须 +1，且永不复用。
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 自建后端（登录/注册/验证码/AI 聊天代理）Base URL，需以 "/" 结尾。
        // AI 请求统一走后端代理，大模型 API Key 只保存在后端，不再打进客户端包内。
        // 在 local.properties 中配置 EDEN_AUTH_BASE_URL=你的后端地址 即可切换。
        buildConfigField(
            "String", "AUTH_BASE_URL",
            "\"${secretOr("EDEN_AUTH_BASE_URL", "https://api.eden.example.com/api/v1/")}\""
        )

        // AI 聊天代理后端 Base URL（即 server/ 里的 FastAPI 服务），需以 "/" 结尾。
        // 默认 10.0.2.2 是 Android 模拟器访问宿主机 localhost 的固定地址。
        // 真机调试时把 EDEN_AI_BASE_URL 配置成电脑局域网 IP，例如 http://192.168.1.10:8001/
        buildConfigField(
            "String", "AI_BASE_URL",
            "\"${secretOr("EDEN_AI_BASE_URL", "http://10.0.2.2:8001/")}\""
        )

        // ===== 直连模式（仅本地调试自用，禁止打入正式包）=====
        // 直连大模型 Base URL，需以 "/" 结尾。本项目使用火山方舟（ARK）OpenAI 兼容接入点。
        // 注意：本账号/模型实测 /api/v1/ 可用，/api/v3/ 不可用，勿随意改。
        buildConfigField(
            "String", "LLM_DIRECT_BASE_URL",
            "\"${secretOr("EDEN_LLM_DIRECT_BASE_URL", "https://ark.cn-beijing.volces.com/api/v1/")}\""
        )

        // 直连 API Key：只在 local.properties 里配置（已 gitignore），不要提交到仓库。
        // 默认留空，只有 debug 构建才会注入真实值（见 buildTypes.debug），正式包永远为空。
        buildConfigField(
            "String", "LLM_DIRECT_API_KEY",
            "\"\""
        )

        // 直连模型名 / 推理接入点 ID。
        buildConfigField(
            "String", "LLM_DIRECT_MODEL",
            "\"${secretOr("EDEN_LLM_DIRECT_MODEL", "doubao-seed-2-0-lite-260428")}\""
        )

        // 隐私政策页面地址：上架前必须换成已备案域名下可公网访问的页面。
        buildConfigField(
            "String", "PRIVACY_POLICY_URL",
            "\"${secretOr("EDEN_PRIVACY_POLICY_URL", "https://example.com/eden/privacy")}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")

            // 正式包的接口地址单独配置，不继承 debug 的局域网 HTTP 地址，避免明文请求打进正式包。
            // 拿到已备案域名后，在 local.properties 里补：
            //   EDEN_RELEASE_AUTH_BASE_URL=https://你的域名/api/v1/
            //   EDEN_RELEASE_AI_BASE_URL=https://你的域名/
            buildConfigField(
                "String", "AUTH_BASE_URL",
                "\"${secretOr("EDEN_RELEASE_AUTH_BASE_URL", "https://api.eden.example.com/api/v1/")}\""
            )
            buildConfigField(
                "String", "AI_BASE_URL",
                "\"${secretOr("EDEN_RELEASE_AI_BASE_URL", "https://api.eden.example.com/")}\""
            )
        }
        debug {
            // 直连大模型仅限本地调试：真实 Key 只注入 debug 变体。
            buildConfigField(
                "String", "LLM_DIRECT_API_KEY",
                "\"${secretOr("EDEN_LLM_DIRECT_API_KEY", "")}\""
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// 出正式包前检查签名配置：缺失时直接失败，避免打包到一半才报签名错误。
// 挂在 preReleaseBuild 上，assembleRelease / bundleRelease 都会先执行到它。
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        releaseSigningProblem()?.let { problem ->
            throw GradleException(
                "正式包签名不可用：$problem。请在 local.properties 补齐 " +
                    "EDEN_RELEASE_STORE_FILE / EDEN_RELEASE_STORE_PASSWORD / " +
                    "EDEN_RELEASE_KEY_ALIAS / EDEN_RELEASE_KEY_PASSWORD 后重试。"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("androidx.activity:activity-ktx:1.8.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.cardview:cardview:1.0.0")
    // Retrofit 网络请求框架
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    // Gson 解析器
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    // OkHttp 客户端
    implementation("com.squareup.okhttp3:okhttp:4.9.3")
    // 加密 SharedPreferences：本地登录凭证（token）加密存储
    implementation("androidx.security:security-crypto:1.1.0")
    // Kotlin 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
    // ViewModel / LiveData（MVVM 架构）
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    // Room 本地数据库（聊天记录持久化）
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)
    // WorkManager 后台定时任务（主动问候）
    implementation(libs.androidx.work.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}