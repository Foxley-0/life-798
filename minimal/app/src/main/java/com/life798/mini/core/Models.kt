package com.life798.mini.core

/** 网络返回的统一封装。 */
sealed interface ApiResult<out T> {
    data class Ok<T>(val data: T) : ApiResult<T>

    /**
     * @param code 服务端业务码；0 表示成功，-99 表示登录态失效
     * @param message 可直接展示给用户的文案
     */
    data class Err(val message: String, val code: Int = 0) : ApiResult<Nothing>
}

/** 登录成功后拿到的东西。 */
data class Session(
    val token: String,
    val uid: String,
    val phone: String
)
