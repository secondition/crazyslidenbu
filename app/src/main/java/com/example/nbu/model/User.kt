package com.example.nbu.model

/**
 * 用户数据模型
 */
data class User(
    val username: String,      // 学号
    val password: String,      // 密码
    val remark: String = ""    // 备注（用于标记用户身份）
) {
    /**
     * 显示名称（用于下拉列表）
     */
    val displayName: String
        get() = if (remark.isNotEmpty()) "$username ($remark)" else username
}
