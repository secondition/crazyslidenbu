package com.example.nbu.model

/**
 * 签到结果
 */
data class SignResult(
    val success: Boolean,
    val code: Int,
    val message: String
)

/**
 * 签到响应
 */
data class SignResponse(
    val code: Int,
    val msg: String?,
    val data: Any?
)
