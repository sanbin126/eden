package com.example.eden.ui.theme

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

interface MimoApi {
    @POST("chat/completions")  // 注意：这里拼接 baseUrl 后是完整地址
    suspend fun getChatCompletion(@Body request: ChatRequest): ChatResponse
}

object ApiClient {
    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain: Interceptor.Chain ->
            val originalRequest: Request = chain.request()
            val authenticatedRequest = originalRequest.newBuilder()
                .addHeader("Authorization", "Bearer ${ApiConfig.API_KEY}")
                .addHeader("Content-Type", "application/json")
                .build()
            chain.proceed(authenticatedRequest)
        }
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(ApiConfig.BASE_URL)   // 必须以 "/" 结尾，已在 ApiConfig 中保证
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val mimoApi: MimoApi = retrofit.create(MimoApi::class.java)
}