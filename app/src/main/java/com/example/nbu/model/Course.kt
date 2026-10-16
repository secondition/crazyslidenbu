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
    val attendanceState: Int?,
    val classroomName: String?,
    val teacherName: String?
) {
    /**
     * 签到功能是否已开放（老师是否开启了签到）
     */
    val isSignOpen: Boolean
        get() = coursePlanId != null && attendanceId != null
    
    /**
     * 是否可以签到（已开启签到且未签到）
     */
    val isSignable: Boolean
        get() = isSignOpen && attendanceState == 0
    
    /**
     * 获取不可签到的原因
     */
    val unsignableReason: String?
        get() = when {
            coursePlanId == null || attendanceId == null -> "该课程未开启签到"
            attendanceState == 1 -> "您已签到"
            attendanceState == 2 -> "您已迟到"
            attendanceState == 3 -> "您已请假"
            attendanceState == 0 -> null
            else -> "当前状态无法签到"
        }
    
    val key: List<String?>
        get() = listOf(courseNumber, courseName, startTime, endTime, classroomName, teacherName)

    val statusLabel: String
        get() = when {
            !isSignOpen -> "未开放"
            attendanceState == 1 -> "已签到"
            attendanceState == 2 -> "迟到"
            attendanceState == 3 -> "请假"
            attendanceState == 0 -> "未签到"
            else -> "状态未知"
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
    val code: Int?,
    val msg: String?,
    val data: List<CourseData>?
)

data class CourseData(
    val courseNumber: String?,
    val courseName: String?,
    val startTime: String?,
    val endTime: String?,
    val coursePlanId: Any?,  // 可能是 String 或 Long
    val attendanceId: Any?,   // 可能是 String 或 Long
    val attendanceState: Any?, // 可能是 Int 或 String
    val classroomName: String?,
    val teacherName: String?
) {
    /**
     * 将 Any 类型的 ID 转换为字符串，处理科学计数法问题
     * Gson 可能将大整数解析为 Double，导致 toString() 产生科学计数法如 "1.73311524E9"
     */
    private fun convertIdToString(value: Any?): String? {
        return when (value) {
            null -> null
            is Number -> {
                // 转为 Long 避免科学计数法
                val longValue = value.toLong()
                if (longValue == 0L) null else longValue.toString()
            }
            is String -> value.takeIf { it != "null" && it.isNotBlank() && it != "0" }
            else -> value.toString().takeIf { it != "null" && it.isNotBlank() && it != "0" }
        }
    }
    
    fun toCourse(): Course {
        return Course(
            courseNumber = courseNumber ?: "",
            courseName = courseName ?: "",
            startTime = startTime ?: "",
            endTime = endTime ?: "",
            coursePlanId = convertIdToString(coursePlanId),
            attendanceId = convertIdToString(attendanceId),
            attendanceState = when (attendanceState) {
                is Number -> attendanceState.toInt()
                is String -> attendanceState.toIntOrNull()
                else -> null
            },
            classroomName = classroomName?.takeIf { it.isNotBlank() },
            teacherName = teacherName?.takeIf { it.isNotBlank() }
        )
    }
}
