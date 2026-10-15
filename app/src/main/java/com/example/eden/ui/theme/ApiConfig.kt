package com.example.eden.ui.theme

import com.example.eden.BuildConfig

/**
 * 网络配置。
 *
 * 所有取值都来自 BuildConfig，而 BuildConfig 由 app/build.gradle.kts 从
 * local.properties（或同名环境变量）读取，避免把地址/密钥硬编码进源码。
 *
 * 注意：大模型 API Key **不再**放在客户端。AI 请求统一走自建代理后端
 * （见项目根目录 server/），密钥只保存在后端环境变量中。
 */
object ApiConfig {

    /**
     * AI 聊天代理后端 Base URL（FastAPI 服务），需以 "/" 结尾。
     * 本地调试默认 http://10.0.2.2:8001/（模拟器访问宿主机 localhost）。
     * 配置项：local.properties 中 EDEN_AI_BASE_URL=...
     */
    val AI_BASE_URL: String = BuildConfig.AI_BASE_URL

    /**
     * 自建后端（账号体系）Base URL，需以 "/" 结尾。
     * 配置项：local.properties 中 EDEN_AUTH_BASE_URL=...
     */
    val AUTH_BASE_URL: String = BuildConfig.AUTH_BASE_URL
}
