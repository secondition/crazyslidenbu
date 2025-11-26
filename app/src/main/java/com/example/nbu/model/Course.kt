package com.example.nbu.model

/**
 * 课程数据模型
 */
data class Course(
    val courseNumber: String,
    val courseName: String,
    val startTime: String,
    val endTime: String,
    val coursePlanId: String?,
    val attendanceId: String?,
    val attendanceState: Int?
) {
    companion object {
        // 测试模式开关：开启后除了未开启签到的课程，其他都能显示签到码输入框
        const val TEST_MODE = false
    }
    
    /**
     * 签到功能是否已开放（老师是否开启了签到）
     */
    val isSignOpen: Boolean
        get() = coursePlanId != null && attendanceId != null
    
    /**
     * 是否可以签到（已开启签到且未签到）
     * 测试模式下：只要开启了签到就可以显示输入框
     */
    val isSignable: Boolean
        get() = if (TEST_MODE) {
            // 测试模式：只要开启签到就能输入签到码
            isSignOpen
        } else {
            // 正常模式：已开启签到且未签到
            isSignOpen && (attendanceState == null || attendanceState == 0)
        }
    
    /**
     * 获取不可签到的原因
     */
    val unsignableReason: String?
        get() = when {
            coursePlanId == null || attendanceId == null -> "该课程未开启签到"
            attendanceState == 1 -> "您已签到"
            attendanceState == 2 -> "您已迟到"
            attendanceState == 3 -> "您已请假"
            else -> null
        }
    
    /**
     * 获取签到状态描述
     */
    val attendanceStateDesc: String
        get() = when (attendanceState) {
            0 -> "未签到"
            1 -> "已签到"
            2 -> "迟到"
            3 -> "请假"
            else -> "未知"
        }
    
    /**
     * 课程显示名称
     */
    val displayName: String
        get() = "$courseNumber-$courseName"
}

/**
 * 获取课程列表的响应
 */
data class CourseResponse(
    val code: Int,
    val msg: String?,
    val data: List<CourseData>?
)

data class CourseData(
    val courseNumber: String?,
    val courseName: String?,
    val startTime: String?,
    val endTime: String?,
    val coursePlanId: String?,
    val attendanceId: String?,
    val attendanceState: Int?
) {
    fun toCourse(): Course {
        return Course(
            courseNumber = courseNumber ?: "",
            courseName = courseName ?: "",
            startTime = startTime ?: "",
            endTime = endTime ?: "",
            coursePlanId = coursePlanId,
            attendanceId = attendanceId,
            attendanceState = attendanceState
        )
    }
}
