package com.example.nbu

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.MenuItem
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.nbu.adapter.CourseAdapter
import com.example.nbu.databinding.ActivitySignBinding
import com.example.nbu.model.ActivityCode
import com.example.nbu.model.Course
import com.example.nbu.network.NbuApiService
import com.example.nbu.network.SessionExpiredException
import com.example.nbu.util.StatusBarUtil
import com.example.nbu.util.UserManager
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 签到界面
 */
class SignActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_JSESSION_ID = "jsession_id"
        const val EXTRA_USERNAME = "username"
    }

    private lateinit var binding: ActivitySignBinding
    private val apiService by lazy { NbuApiService(username, userManager) }
    private lateinit var adapter: CourseAdapter
    private lateinit var userManager: UserManager
    
    private var jsessionId: String = ""
    private var username: String = ""
    private var courseJob: Job? = null
    private lateinit var scan: MenuItem

    private val scanner = registerForActivityResult(ScanContract()) { result ->
        scan.isEnabled = true
        val value = result.contents ?: return@registerForActivityResult
        val code = try {
            ActivityCode.parse(value)
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, e.message ?: getString(R.string.activity_failed), Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        startActivity(Intent(this, WebActivity::class.java)
            .putExtra(WebActivity.EXTRA_USERNAME, username)
            .putExtra(WebActivity.EXTRA_URL, code.url))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // 设置状态栏颜色
        StatusBarUtil.setStatusBarColor(this, ContextCompat.getColor(this, R.color.header))

        userManager = UserManager(this)
        
        jsessionId = intent.getStringExtra(EXTRA_JSESSION_ID) ?: ""
        username = intent.getStringExtra(EXTRA_USERNAME) ?: ""
        
        if (jsessionId.isEmpty() || username.isEmpty()) {
            Toast.makeText(this, "Session 无效，请重新登录", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupViews()
        loadCourses()
    }

    private fun navigateToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun setupViews() {
        adapter = CourseAdapter { course, code ->
            handleSignClick(course, code)
        }

        binding.rvCourses.layoutManager = LinearLayoutManager(this)
        binding.rvCourses.adapter = adapter

        // 设置下拉刷新
        binding.swipeRefresh.setColorSchemeResources(R.color.brand_primary)
        binding.swipeRefresh.setOnRefreshListener {
            loadCourses()
        }

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        scan = binding.toolbar.menu.add(R.string.activity_scan)
        scan.setIcon(R.drawable.ic_qr_code)
        scan.icon?.mutate()?.setTint(getColor(R.color.on_header))
        scan.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        binding.toolbar.setOnMenuItemClickListener {
            getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(binding.root.windowToken, 0)
            scan.isEnabled = false
            scanner.launch(ScanOptions()
                .setCaptureActivity(ScanActivity::class.java)
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.scan_prompt))
                .addExtra(Intents.Scan.SHOW_MISSING_CAMERA_PERMISSION_DIALOG, true)
                .addExtra(Intents.Scan.MISSING_CAMERA_PERMISSION_DIALOG_MESSAGE, getString(R.string.camera_permission)))
            true
        }
    }

    private fun loadCourses() {
        courseJob?.cancel()
        setLoadingState(true, "正在获取课程列表...")

        courseJob = lifecycleScope.launch {
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
                if (error is SessionExpiredException) {
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
                    Toast.makeText(this@SignActivity, "签到失败: ${signResult.message}", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                if (error is SessionExpiredException) {
                    handleSessionExpired()
                } else {
                    Toast.makeText(this@SignActivity, "签到出错: ${error.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setLoadingState(isLoading: Boolean, message: String = "") {
        if (isLoading) {
            // 首次加载时显示加载视图，下拉刷新时不显示
            if (!binding.swipeRefresh.isRefreshing) {
                binding.loadingView.visibility = View.VISIBLE
                binding.rvCourses.visibility = View.GONE
                binding.emptyView.visibility = View.GONE
                binding.tvLoadingStatus.text = message
            }
        } else {
            binding.loadingView.visibility = View.GONE
            binding.swipeRefresh.isRefreshing = false
        }
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
