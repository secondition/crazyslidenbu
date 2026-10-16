package com.example.nbu.adapter

import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.RecyclerView
import com.example.nbu.R
import com.example.nbu.model.GroupCourse

/**
 * 课程列表适配器
 */
class CourseAdapter(
    private val names: Map<String, String>,
    private val onSignClick: (GroupCourse, String?) -> Unit
) : RecyclerView.Adapter<CourseAdapter.CourseViewHolder>() {

    private val courses = mutableListOf<GroupCourse>()
    private val expandedPositions = mutableSetOf<Int>()
    private var busy = false

    fun setCourses(newCourses: List<GroupCourse>) {
        courses.clear()
        courses.addAll(newCourses)
        expandedPositions.clear()
        notifyDataSetChanged()
    }

    fun setBusy(value: Boolean) {
        busy = value
        notifyItemRangeChanged(0, courses.size)
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
        private val tvMembers: TextView = itemView.findViewById(R.id.tvMembers)
        private val signArea: View = itemView.findViewById(R.id.signArea)
        private val etCode: EditText = itemView.findViewById(R.id.etCode)
        
        private var textWatcher: TextWatcher? = null

        fun bind(group: GroupCourse) {
            val course = group.course
            etCode.isEnabled = !busy
            itemView.isEnabled = !busy
            tvMembers.visibility = if (names.size > 1) View.VISIBLE else View.GONE
            tvMembers.text = group.courses.entries.joinToString("\n") { (username, memberCourse) ->
                "${names[username]}：${memberCourse.statusLabel}"
            }
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
                !group.isSignOpen -> {
                    // 未开放签到（老师未开启签到）
                    iconRes = R.drawable.ic_status_lock
                    iconTint = Color.GRAY
                }
                group.attendanceState == 1 -> {
                    // 已签到
                    iconRes = R.drawable.ic_status_check
                    iconTint = "#4CAF50".toColorInt() // Green
                }
                group.attendanceState == 2 -> {
                    // 迟到
                    iconRes = R.drawable.ic_status_late
                    iconTint = "#FF9800".toColorInt() // Orange
                }
                group.attendanceState == 3 -> {
                    // 请假
                    iconRes = R.drawable.ic_status_leave
                    iconTint = "#2196F3".toColorInt() // Blue
                }
                else -> {
                    // 未签到（签到已开放但尚未签到，attendanceState == 0 或 null）
                    iconRes = R.drawable.ic_status_close
                    iconTint = "#F44336".toColorInt() // Red
                }
            }

            ivStatus.setImageResource(iconRes)
            ivStatus.setColorFilter(iconTint)

            // 签到区域显示逻辑：课程可签到且处于展开状态时显示
            val isExpanded = expandedPositions.contains(adapterPosition)
            if (group.isSignable && isExpanded) {
                signArea.visibility = View.VISIBLE
            } else {
                signArea.visibility = View.GONE
            }
            
            // 移除旧的 TextWatcher，防止重复添加
            etCode.removeTextChangedListener(textWatcher)
            
            // 创建新的 TextWatcher
            textWatcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (s != null && s.length == 4) {
                        val code = s.toString()
                        // 自动签到
                        onSignClick(group, code)
                        // 清空输入框
                        etCode.setText("")
                    }
                }
            }
            etCode.addTextChangedListener(textWatcher)
            
            // 点击整个卡片切换展开状态
            itemView.setOnClickListener {
                if (group.isSignable) {
                    if (expandedPositions.contains(adapterPosition)) {
                        expandedPositions.remove(adapterPosition)
                    } else {
                        expandedPositions.add(adapterPosition)
                    }
                    notifyItemChanged(adapterPosition)
                } else {
                    // 不可签到时显示原因
                    val reason = if (names.size == 1) course.unsignableReason
                        else itemView.context.getString(R.string.no_signable_members)
                    reason?.let { Toast.makeText(itemView.context, it, Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }
}
