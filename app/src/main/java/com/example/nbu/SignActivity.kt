package com.example.nbu

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.nbu.adapter.CourseAdapter
import com.example.nbu.databinding.ActivitySignBinding
import com.example.nbu.model.Course
import com.example.nbu.network.NbuApiService
import com.example.nbu.util.UserManager
import kotlinx.coroutines.launch

/**
 * 签到界面
 * 对接 signtest.py 的签到逻辑
 */
class SignActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_JSESSION_ID = "jsession_id"
        const val EXTRA_USERNAME = "username"
    }

    private lateinit var binding: ActivitySignBinding
    private val apiService = NbuApiService()
    private lateinit var adapter: CourseAdapter
    private lateinit var userManager: UserManager
    
    private var jsessionId: String = ""
    private var username: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignBinding.inflate(layoutInflater)
        setContentView(binding.root)

        userManager = UserManager(this)
        
        jsessionId = intent.getStringExtra(EXTRA_JSESSION_ID) ?: ""
        username = intent.getStringExtra(EXTRA_USERNAME) ?: ""
        
        if (jsessionId.isEmpty()) {
            Toast.makeText(this, "Session 无效，请重新登录", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupViews()
        loadCourses()
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_sign, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_logout -> {
                showLogoutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun showLogoutDialog() {
        AlertDialog.Builder(this)
            .setTitle("退出登录")
            .setMessage("确定要退出登录吗？这将清除已缓存的登录状态。")
            .setPositiveButton("确定") { _, _ ->
                logout()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun logout() {
        // 清除该用户的 Session 缓存
        if (username.isNotEmpty()) {
            userManager.clearSession(username)
        }
        
        // 返回登录界面
        navigateToLogin()
    }
    
    private fun navigateToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun setupViews() {
        setSupportActionBar(binding.toolbar)
        
        adapter = CourseAdapter { course, code ->
            handleSignClick(course, code)
        }

        binding.rvCourses.layoutManager = LinearLayoutManager(this)
        binding.rvCourses.adapter = adapter

        binding.btnRefresh.setOnClickListener {
            loadCourses()
        }

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun loadCourses() {
        setLoadingState(true, "正在获取课程列表...")

        lifecycleScope.launch {
            val result = apiService.getCourses(jsessionId)

            result.onSuccess { courses ->
                setLoadingState(false)
                
                if (courses.isEmpty()) {
                    showEmptyState()
                } else {
                    showCourseList(courses)
                }
            }.onFailure { error ->
                setLoadingState(false)
                
                val errorMsg = error.message ?: ""
                // 检查是否是 Session 失效
                if (errorMsg.contains("未登录") || errorMsg.contains("登录失效") || errorMsg.contains("401")) {
                    handleSessionExpired()
                } else {
                    Toast.makeText(
                        this@SignActivity, 
                        "获取课程失败: $errorMsg", 
                        Toast.LENGTH_LONG
                    ).show()
                    showEmptyState()
                }
            }
        }
    }
    
    private fun handleSessionExpired() {
        // 清除该用户的 Session 缓存
        if (username.isNotEmpty()) {
            userManager.clearSession(username)
        }
        
        Toast.makeText(this, "登录状态已失效，请重新登录", Toast.LENGTH_SHORT).show()
        
        // 返回登录界面，用户重新点击登录会自动通过 UIS 获取新 Session
        navigateToLogin()
    }

    private fun handleSignClick(course: Course, code: String?) {
        if (!course.isSignable) {
            Toast.makeText(this, "该课程未开启签到", Toast.LENGTH_SHORT).show()
            return
        }

        val coursePlanId = course.coursePlanId ?: return
        val attendanceId = course.attendanceId ?: return

        if (code != null && code.length == 4 && code.all { it.isDigit() }) {
            // 使用指定的签到码
            performSign(coursePlanId, attendanceId, code.toInt())
        } else {
            Toast.makeText(this, "请输入4位数字签到码", Toast.LENGTH_SHORT).show()
        }
    }

    private fun performSign(coursePlanId: String, attendanceId: String, code: Int) {
        lifecycleScope.launch {
            val result = apiService.sign(jsessionId, coursePlanId, attendanceId, code)

            result.onSuccess { signResult ->
                if (signResult.success) {
                    Toast.makeText(
                        this@SignActivity, 
                        "✓ 签到成功！签到码: ${String.format("%04d", code)}", 
                        Toast.LENGTH_LONG
                    ).show()
                    // 刷新课程列表
                    loadCourses()
                } else {
                    Toast.makeText(
                        this@SignActivity, 
                        "签到失败: ${signResult.message}", 
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }.onFailure { error ->
                Toast.makeText(
                    this@SignActivity, 
                    "签到出错: ${error.message}", 
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun setLoadingState(isLoading: Boolean, message: String = "") {
        if (isLoading) {
            binding.loadingView.visibility = View.VISIBLE
            binding.rvCourses.visibility = View.GONE
            binding.emptyView.visibility = View.GONE
            binding.tvLoadingStatus.text = message
        } else {
            binding.loadingView.visibility = View.GONE
        }
        binding.btnRefresh.isEnabled = !isLoading
    }

    private fun showEmptyState() {
        binding.emptyView.visibility = View.VISIBLE
        binding.rvCourses.visibility = View.GONE
    }

    private fun showCourseList(courses: List<Course>) {
        binding.emptyView.visibility = View.GONE
        binding.rvCourses.visibility = View.VISIBLE
        adapter.setCourses(courses)
    }
}
