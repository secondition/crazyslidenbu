package com.example.nbu

import com.example.nbu.model.Course
import com.example.nbu.model.GroupCourse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupCourseTest {
    @Test
    fun mergedCoursesRetainMemberRecordsAndOnlyUnsignedCanSubmit() {
        val first = Course("001", "课程", "08:00", "09:50", "plan-a", "record-a", 0, "101", "教师")
        val second = first.copy(coursePlanId = "plan-b", attendanceId = "record-b", attendanceState = 3)
        val other = first.copy(courseNumber = "002")
        val groups = GroupCourse.merge(mapOf("a" to listOf(first, other), "b" to listOf(second)))
        assertEquals(2, groups.size)
        val shared = groups.first { it.course.courseNumber == "001" }
        assertEquals("record-a", shared.courses.getValue("a").attendanceId)
        assertEquals("record-b", shared.courses.getValue("b").attendanceId)
        assertTrue(shared.isSignable)
        for (state in listOf(null, 1, 2, 3, 4)) {
            assertFalse(first.copy(attendanceState = state).isSignable)
        }
        assertFalse(first.copy(attendanceId = null).isSignable)
        val completed = GroupCourse(mapOf("a" to first.copy(attendanceState = 1), "b" to second))
        assertFalse(completed.isSignable)
        assertEquals(1, completed.attendanceState)
    }
}
