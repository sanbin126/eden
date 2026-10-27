package com.example.eden.core.session

/**
 * 账号体系（登录/注册/验证码）相关数据模型。
 *
 * 采用通用 REST 约定：所有接口返回统一信封 [ApiResponse]，
 * code == 0 表示成功，其余为业务错误码，message 为提示文案。
 * 具体字段以实际后端文档为准，若后端字段不同，仅需调整本文件的 DTO。
 */

/** 统一响应信封 */
data class ApiResponse<T>(
    val code: Int = 0,
    val message: String? = null,
    val data: T? = null
)

/** 发送邮箱验证码 */
data class SendCodeRequest(
    val email: String,
    /** 场景：register 注册 / login 登录 / reset 找回密码 */
    val scene: String
)

data class SendCodeData(
    /** 验证码有效期（秒），后端可不返回 */
    val expireIn: Int? = null
)

/** 注册：邮箱 + 验证码 + 密码 */
data class RegisterRequest(
    val email: String,
    val code: String,
    val password: String
)

/** 密码登录 */
data class PasswordLoginRequest(
    val email: String,
    val password: String
)

/** 验证码登录 */
data class CodeLoginRequest(
    val email: String,
    val code: String
)

/** 修改密码（需登录态） */
data class ChangePasswordRequest(
    val oldPassword: String,
    val newPassword: String
)

/** 忘记密码：邮箱 + 验证码 + 新密码（无需登录态） */
data class ResetPasswordRequest(
    val email: String,
    val code: String,
    val newPassword: String
)

/** 登录/注册成功后的数据 */
data class AuthData(
    val token: String? = null,
    val user: UserInfo? = null
)

/** 用户信息 */
data class UserInfo(
    val id: Long? = null,
    val email: String? = null,
    val nickname: String? = null,
    val avatar: String? = null
)

/** 验证码场景常量 */
object CodeScene {
    const val REGISTER = "register"
    const val LOGIN = "login"
    const val RESET = "reset"
}
