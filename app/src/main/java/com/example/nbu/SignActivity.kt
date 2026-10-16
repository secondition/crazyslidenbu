package com.example.nbu

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
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
import com.example.nbu.model.GroupCourse
import com.example.nbu.model.User
import com.example.nbu.network.NbuApiService
import com.example.nbu.network.SessionExpiredException
import com.example.nbu.util.StatusBarUtil
import com.example.nbu.util.UserManager
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

/**
 * 签到界面
 */
class SignActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_USERNAMES = "usernames"
    }

    private lateinit var binding: ActivitySignBinding
    private lateinit var adapter: CourseAdapter
    private lateinit var userManager: UserManager
    private lateinit var users: List<User>
    private lateinit var services: Map<String, NbuApiService>
    private var courseJob: Job? = null
    private var signJob: Job? = null
    private lateinit var scan: MenuItem
    private var resultSummary = ""

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
            .putStringArrayListExtra(WebActivity.EXTRA_USERNAMES, ArrayList(users.map { it.username }))
            .putExtra(WebActivity.EXTRA_URL, code.url))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignBinding.inflate(layoutInflater)
        setContentView(binding.root)
        StatusBarUtil.setStatusBarColor(this, ContextCompat.getColor(this, R.color.header))
        userManager = UserManager(this)
        val usernames = intent.getStringArrayListExtra(EXTRA_USERNAMES).orEmpty().distinct()
        users = usernames.mapNotNull(userManager::getUser)
        if (users.isEmpty() || users.size != usernames.size) {
            Toast.makeText(this, R.string.group_login_required, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        services = users.associate { it.username to NbuApiService(it.username, userManager) }
        resultSummary = savedInstanceState?.getString("summary").orEmpty()
        setupViews()
        loadCourses()
    }

    private fun setupViews() {
        adapter = CourseAdapter(users.associate { it.username to it.displayName }) { course, code ->
            handleSignClick(course, code)
        }
        binding.rvCourses.layoutManager = LinearLayoutManager(this)
        binding.rvCourses.adapter = adapter
        binding.swipeRefresh.setColorSchemeResources(R.color.brand_primary)
        binding.swipeRefresh.setOnRefreshListener { loadCourses() }
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        if (users.size > 1) binding.toolbar.subtitle = getString(R.string.group_member_count, users.size)
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
        setLoadingState(true, getString(R.string.loading_courses))
        courseJob = lifecycleScope.launch {
            try {
                val failures = mutableMapOf<String, String>()
                val courses = users.map { user ->
                    async {
                        val courses = try {
                            val session = userManager.getCachedSession(user.username) ?: throw SessionExpiredException()
                            services.getValue(user.username).getCourses(session).getOrThrow()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (e is SessionExpiredException) userManager.clearSession(user.username)
                            failures[user.username] = "${user.displayName}：${e.message}"
                            emptyList()
                        }
                        user.username to courses
                    }
                }.awaitAll().toMap()
                val combined = GroupCourse.merge(courses)
                binding.emptyView.visibility = if (combined.isEmpty() && failures.isEmpty()) View.VISIBLE else View.GONE
                binding.rvCourses.visibility = if (combined.isEmpty()) View.GONE else View.VISIBLE
                adapter.setCourses(combined)
                binding.tvGroupStatus.text = (listOf(resultSummary) + users.mapNotNull { failures[it.username] })
                    .filter { it.isNotBlank() }.joinToString("\n")
                binding.tvGroupStatus.visibility = if (binding.tvGroupStatus.text.isEmpty()) View.GONE else View.VISIBLE
            } finally {
                setLoadingState(false)
            }
        }
    }

    private fun handleSignClick(group: GroupCourse, code: String?) {
        if (signJob?.isActive == true) return
        if (!group.isSignable) {
            Toast.makeText(this, R.string.no_signable_members, Toast.LENGTH_SHORT).show()
            return
        }
        if (code == null || code.length != 4 || !code.all { it.isDigit() }) {
            Toast.makeText(this, R.string.sign_code_required, Toast.LENGTH_SHORT).show()
            return
        }
        scan.isEnabled = false
        adapter.setBusy(true)
        binding.swipeRefresh.isEnabled = false
        signJob = lifecycleScope.launch {
            try {
                val results = users.associate { it.username to getString(R.string.activity_processing) }.toMutableMap()
                fun renderResults() {
                    resultSummary = users.joinToString("\n") { "${it.displayName}：${results.getValue(it.username)}" }
                    binding.tvGroupStatus.text = resultSummary
                }
                binding.tvGroupStatus.visibility = View.VISIBLE
                renderResults()
                users.map { user -> async {
                    val message = try {
                        val session = userManager.getCachedSession(user.username) ?: throw SessionExpiredException()
                        val api = services.getValue(user.username)
                        val course = api.getCourses(session).getOrThrow().firstOrNull { it.key == group.course.key }
                        when {
                            course == null -> getString(R.string.no_matching_course)
                            !course.isSignable -> course.statusLabel
                            else -> {
                                val result = api.sign(session, requireNotNull(course.coursePlanId),
                                    requireNotNull(course.attendanceId), code.toInt()).getOrThrow()
                                if (result.success) "✓ ${result.message}" else getString(R.string.sign_failed, result.message)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (e is SessionExpiredException) userManager.clearSession(user.username)
                        getString(R.string.sign_failed, e.message.orEmpty())
                    }
                    results[user.username] = message
                    renderResults()
                } }.awaitAll()
                loadCourses()
            } finally {
                scan.isEnabled = true
                adapter.setBusy(false)
                binding.swipeRefresh.isEnabled = true
            }
        }
    }

    private fun setLoadingState(isLoading: Boolean, message: String = "") {
        if (isLoading) {
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("summary", resultSummary)
    }
}
