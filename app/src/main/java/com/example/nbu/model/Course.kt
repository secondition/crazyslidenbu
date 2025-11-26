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
    /**
     * 是否可以签到（已开启签到且未签到）
     */
    val isSignable: Boolean
        get() = coursePlanId != null && attendanceId != null && (attendanceState == null || attendanceState == 0)
    
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
