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

android {
    namespace = "com.example.eden"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.eden"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

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
        buildConfigField(
            "String", "LLM_DIRECT_API_KEY",
            "\"${secretOr("EDEN_LLM_DIRECT_API_KEY", "")}\""
        )

        // 直连模型名 / 推理接入点 ID。
        buildConfigField(
            "String", "LLM_DIRECT_MODEL",
            "\"${secretOr("EDEN_LLM_DIRECT_MODEL", "doubao-seed-2-0-lite-260428")}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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