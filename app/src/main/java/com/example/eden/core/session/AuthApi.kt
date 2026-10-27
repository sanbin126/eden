package com.example.eden.core.session

import com.example.eden.EdenApp
import com.example.eden.core.network.ApiConfig
import com.example.eden.core.network.RetryInterceptor
import com.google.gson.JsonParseException
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * 自建后端账号接口（通用 REST 约定，路径相对于 ApiConfig.AUTH_BASE_URL）。
 *
 * 若后端实际路径/字段与下述不一致，只需修改本接口与 [AuthModels] 中的 DTO。
 */
interface AuthApi {

    /** 发送邮箱验证码 */
    @POST("auth/email/code")
    suspend fun sendCode(@Body request: SendCodeRequest): ApiResponse<SendCodeData>

    /** 注册 */
    @POST("auth/register")
    suspend fun register(@Body request: RegisterRequest): ApiResponse<AuthData>

    /** 密码登录 */
    @POST("auth/login")
    suspend fun login(@Body request: PasswordLoginRequest): ApiResponse<AuthData>

    /** 验证码登录 */
    @POST("auth/login/code")
    suspend fun loginByCode(@Body request: CodeLoginRequest): ApiResponse<AuthData>

    /** 忘记密码：用邮箱验证码重置密码（无需登录态） */
    @POST("auth/password/reset")
    suspend fun resetPassword(@Body request: ResetPasswordRequest): ApiResponse<Any>

    /** 修改密码（需要 Bearer 凭证） */
    @POST("auth/password/change")
    suspend fun changePassword(
        @Header("Authorization") authorization: String,
        @Body request: ChangePasswordRequest
    ): ApiResponse<Any>

    /** 获取当前用户信息（需要 Bearer 凭证） */
    @GET("user/profile")
    suspend fun getProfile(@Header("Authorization") authorization: String): ApiResponse<UserInfo>

    /** 拉取当前用户的全部业务数据（需要 Bearer 凭证），登录成功后调用 */
    @GET("sync/data")
    suspend fun pullSyncData(
        @Header("Authorization") authorization: String
    ): ApiResponse<SyncData>

    /** 上传 / 更新业务数据（需要 Bearer 凭证），本地数据变更后调用 */
    @POST("sync/data")
    suspend fun pushSyncData(
        @Header("Authorization") authorization: String,
        @Body request: SyncPushRequest
    ): ApiResponse<Any>
}

object AuthClient {
    private val okHttpClient = OkHttpClient.Builder()
        // 超时放宽到 60s：同步接口会一次性上传/下载全部业务数据（含聊天记录），
        // 弱网或数据量较大时 15s 容易误判超时。
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // 指数退避重试：500 等 5xx 及网络超时自动重试，最多 3 次，间隔 1s / 2s 逐步拉长。
        .addInterceptor(RetryInterceptor(maxRetries = 3, baseDelayMs = 1000L))
        // Token 过期统一处理：除登录/注册等匿名接口外，任何接口返回 401 都视为登录态失效，
        // 交由 EdenApp 清空本地会话并跳回登录页。
        .addInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            val path = request.url.encodedPath
            if (response.code == 401 && !path.contains("/auth/")) {
                EdenApp.onUnauthorized()
            }
            response
        }
        .build()

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(ApiConfig.AUTH_BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val api: AuthApi = retrofit.create(AuthApi::class.java)
}

/**
 * 把账号接口抛出的异常翻译成用户可读的提示。
 *
 * 细分常见失败原因（域名解析 / 连接被拒 / 超时 / HTTP 错误码 / 报文解析），
 * 避免所有失败都笼统提示"网络异常"而掩盖真实问题（如后端地址未配置）。
 */
fun authErrorMessage(e: Throwable): String = when (e) {
    is UnknownHostException -> "无法解析服务器地址，请检查网络或后端地址配置"
    is ConnectException -> "无法连接服务器，请确认后端已启动且地址正确"
    is SocketTimeoutException -> "连接服务器超时，请稍后重试"
    is SSLException -> "安全连接失败，请检查后端证书配置"
    is HttpException -> when (val code = e.code()) {
        404 -> "接口不存在（HTTP 404），请检查后端路径配置"
        in 500..599 -> "服务器错误（HTTP $code），请稍后重试"
        else -> "请求失败（HTTP $code）"
    }
    is JsonParseException -> "响应数据解析失败，请检查后端返回格式"
    is IOException -> "网络连接失败，请检查网络后重试"
    else -> "请求失败：${e.message ?: "未知错误"}"
}
