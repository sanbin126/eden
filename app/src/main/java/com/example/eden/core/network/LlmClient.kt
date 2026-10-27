package com.example.eden.core.network

import okhttp3.ResponseBody

/**
 * 统一 LLM 请求入口。
 *
 * 当前策略：**自动判定、无需用户选择**（见 [LlmModeManager.resolve]）——
 * 默认走后端代理；后端探活不通时自动回退直连火山方舟。
 * 联网搜索能力只在后端模式下生效（搜索密钥与工具循环都在后端）。
 *
 * 所有上层业务（对话 / 记忆压缩等）只调用这里，上层不感知实现差异。
 */
object LlmClient {

    suspend fun chat(request: ChatRequest): ChatResponse =
        when (LlmModeManager.resolve()) {
            LlmMode.BACKEND -> BackendGateway.chat(request)
            LlmMode.DIRECT -> DirectGateway.chat(request)
        }

    suspend fun chatStream(request: ChatRequest): ResponseBody =
        when (LlmModeManager.resolve()) {
            LlmMode.BACKEND -> BackendGateway.chatStream(request)
            LlmMode.DIRECT -> DirectGateway.chatStream(request)
        }
}

// 后端代理分支：完全沿用现有 ApiClient，后端接口零改动。
private object BackendGateway {
    suspend fun chat(request: ChatRequest): ChatResponse = ApiClient.chatApi.chat(request)
    suspend fun chatStream(request: ChatRequest): ResponseBody = ApiClient.chatApi.chatStream(request)
}
