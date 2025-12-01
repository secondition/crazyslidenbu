package com.example.nbu.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.example.nbu.R
import com.example.nbu.model.Course

/**
 * 课程列表适配器
 */
class CourseAdapter(
    private val onSignClick: (Course, String?) -> Unit
) : RecyclerView.Adapter<CourseAdapter.CourseViewHolder>() {

    private val courses = mutableListOf<Course>()
    private val expandedPositions = mutableSetOf<Int>()

    fun setCourses(newCourses: List<Course>) {
        courses.clear()
        courses.addAll(newCourses)
        expandedPositions.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CourseViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_course, parent, false)
        return CourseViewHolder(view)
    }

    override fun onBindViewHolder(holder: CourseViewHolder, position: Int) {
        holder.bind(courses[position])
    }

    override fun getItemCount(): Int = courses.size

    inner class CourseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvCourseName: TextView = itemView.findViewById(R.id.tvCourseName)
        private val ivStatus: ImageView = itemView.findViewById(R.id.ivStatus)
        private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        private val tvClassroom: TextView = itemView.findViewById(R.id.tvClassroom)
        private val tvTeacher: TextView = itemView.findViewById(R.id.tvTeacher)
        private val signArea: View = itemView.findViewById(R.id.signArea)
        private val etCode: EditText = itemView.findViewById(R.id.etCode)
        private val btnSign: Button = itemView.findViewById(R.id.btnSign)

        fun bind(course: Course) {
            tvCourseName.text = course.displayName
            tvTime.text = "${course.startTime} ~ ${course.endTime}"
            
            // 显示教室名称
            if (!course.classroomName.isNullOrBlank()) {
                tvClassroom.text = course.classroomName
                tvClassroom.visibility = View.VISIBLE
            } else {
                tvClassroom.visibility = View.GONE
            }
            
            // 显示教师名称
            if (!course.teacherName.isNullOrBlank()) {
                tvTeacher.text = course.teacherName
                tvTeacher.visibility = View.VISIBLE
            } else {
                tvTeacher.visibility = View.GONE
            }

            // 设置签到状态图标
            // 逻辑：未开放签到显示锁定，已开放签到根据签到状态显示对应图标
            val iconRes: Int
            val iconTint: Int

            when {
                !course.isSignOpen -> {
                    // 未开放签到（老师未开启签到）
                    iconRes = R.drawable.ic_status_lock
                    iconTint = Color.GRAY
                }
                course.attendanceState == 1 -> {
                    // 已签到
                    iconRes = R.drawable.ic_status_check
                    iconTint = Color.parseColor("#4CAF50") // Green
                }
                course.attendanceState == 2 -> {
                    // 迟到
                    iconRes = R.drawable.ic_status_late
                    iconTint = Color.parseColor("#FF9800") // Orange
                }
                course.attendanceState == 3 -> {
                    // 请假
                    iconRes = R.drawable.ic_status_leave
                    iconTint = Color.parseColor("#2196F3") // Blue
                }
                else -> {
                    // 未签到（签到已开放但尚未签到，attendanceState == 0 或 null）
                    iconRes = R.drawable.ic_status_close
                    iconTint = Color.parseColor("#F44336") // Red
                }
            }

            ivStatus.setImageResource(iconRes)
            ivStatus.setColorFilter(iconTint)

            // 签到区域显示逻辑：课程可签到且处于展开状态时显示
            val isExpanded = expandedPositions.contains(adapterPosition)
            if (course.isSignable && isExpanded) {
                signArea.visibility = View.VISIBLE
            } else {
                signArea.visibility = View.GONE
            }
            
            // 点击整个卡片切换展开状态
            itemView.setOnClickListener {
                if (course.isSignable) {
                    if (expandedPositions.contains(adapterPosition)) {
                        expandedPositions.remove(adapterPosition)
                    } else {
                        expandedPositions.add(adapterPosition)
                    }
                    notifyItemChanged(adapterPosition)
                } else {
                    // 不可签到时显示原因
                    course.unsignableReason?.let { reason ->
                        Toast.makeText(itemView.context, reason, Toast.LENGTH_SHORT).show()
                    }
                }
            }

            btnSign.setOnClickListener {
                val code = etCode.text?.toString()?.trim()
                onSignClick(course, code?.takeIf { it.isNotEmpty() })
            }
        }
    }
}
