package com.example.nbu.model

data class GroupCourse(val courses: Map<String, Course>) {
    val course = courses.values.first()
    val isSignOpen = courses.values.any { it.isSignOpen }
    val isSignable = courses.values.any { it.isSignable }
    val attendanceState = courses.values.map {
        if (it.attendanceState == 3) 1 else it.attendanceState
    }.distinct().singleOrNull()

    companion object {
        fun merge(courses: Map<String, List<Course>>): List<GroupCourse> = courses.flatMap { (username, list) ->
            list.map { username to it }
        }.groupBy { (_, course) ->
            course.key
        }.values.map { GroupCourse(it.toMap()) }
            .sortedWith(compareBy({ it.course.startTime }, { it.course.courseNumber }))
    }
}
