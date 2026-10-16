package com.example.nbu

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.example.nbu.databinding.ActivityLoginBinding
import com.example.nbu.model.User
import com.example.nbu.network.NbuApiService
import com.example.nbu.util.AccountCode
import com.example.nbu.util.StatusBarUtil
import com.example.nbu.util.UserManager
import com.example.nbu.util.requestVerificationCode
import com.google.android.material.textfield.TextInputLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * 登录界面
 */
class LoginActivity : AppCompatActivity() {
    private enum class Page { LOGIN, ACCOUNTS, EDITOR, SHARE, REMOVE }

    private companion object {
        const val CAMPUS_PACKAGE = "com.wisedu.cpdaily.nbu"
        const val AD_SECONDS = 3
        const val SHAKE_FORCE = 15f
        const val SHAKE_INTERVAL = 1_000L
    }

    private lateinit var binding: ActivityLoginBinding
    private lateinit var userManager: UserManager
    private var selectedUser: User? = null
    private var currentAccount: User? = null
    private var page = Page.LOGIN
    private var roundTrips = 0
    private var validation: Job? = null
    private var authentication: Job? = null
    private var adCountdown: Job? = null
    private val selectedAccounts = linkedSetOf<String>()

    private val signActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    private val accountScanner = registerForActivityResult(ScanContract()) { result ->
        val value = result.contents ?: return@registerForActivityResult
        val received = try {
            AccountCode.decode(value)
        } catch (e: Exception) {
            showError(getString(R.string.invalid_account_code))
            return@registerForActivityResult
        }
        val existing = userManager.getUser(received.username)
        if (existing != null && existing.password != received.password) userManager.clearAuthentication(received.username)
        val user = received.copy(remark = existing?.remark.orEmpty())
        userManager.saveUser(user)
        fillAccount(user)
        showAccounts()
        Toast.makeText(this, R.string.account_added, Toast.LENGTH_SHORT).show()
    }

    private val photoPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES
            else Manifest.permission.READ_EXTERNAL_STORAGE

    private val photoLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[photoPermission] == true) readLocalDevice()
        else {
            setLoadingState(false)
            showError(getString(R.string.device_permission))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        StatusBarUtil.setStatusBarColor(this, ContextCompat.getColor(this, R.color.header))
        userManager = UserManager(this)
        setupViews()
        loadLastLogin()
        if (savedInstanceState != null) {
            selectedUser = savedInstanceState.getString("selectedUser")?.let(userManager::getUser)
            binding.passwordField.visibility = if (selectedUser == null) View.VISIBLE else View.GONE
        }
        roundTrips = savedInstanceState?.getInt("roundTrips") ?: 0
        selectedAccounts.addAll(savedInstanceState?.getStringArrayList("group").orEmpty())
        currentAccount = savedInstanceState?.getString("account")?.let(userManager::getUser)
        when (Page.valueOf(savedInstanceState?.getString("page") ?: Page.LOGIN.name)) {
            Page.LOGIN -> showPage(Page.LOGIN)
            Page.ACCOUNTS -> showAccounts()
            Page.EDITOR -> showEditor(currentAccount)
            Page.SHARE -> currentAccount?.let(::showAccountCode) ?: showAccounts()
            Page.REMOVE -> currentAccount?.let(::showRemoval) ?: showAccounts()
        }
        onBackPressedDispatcher.addCallback(this) {
            when (page) {
                Page.LOGIN -> finish()
                Page.ACCOUNTS -> showPage(Page.LOGIN)
                else -> showAccounts()
            }
        }
        if (savedInstanceState == null && userManager.isSplashAdEnabled()) showSplashAd()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("page", page.name)
        outState.putString("account", currentAccount?.username)
        outState.putString("selectedUser", selectedUser?.username)
        outState.putInt("roundTrips", roundTrips)
        outState.putStringArrayList("group", ArrayList(selectedAccounts))
    }

    private fun setupViews() {
        binding.etUsername.doAfterTextChanged {
            if (selectedUser != null && it.toString() != selectedUser?.username) {
                selectedUser = null
                binding.passwordField.visibility = View.VISIBLE
            }
        }
        binding.usernameSlider.onClear = { binding.etUsername.text?.clear() }
        binding.usernameSlider.onRoundTrip = {
            roundTrips++
            if (roundTrips == 3) {
                roundTrips = 0
                hideKeyboard()
                showAccounts()
            }
        }
        binding.passwordSlider.onClear = { binding.etPassword.text?.clear() }
        binding.passwordSlider.onHoldRight = {
            val enabled = !userManager.isSplashAdEnabled()
            userManager.setSplashAdEnabled(enabled)
            Toast.makeText(this, if (enabled) R.string.ad_enabled else R.string.ad_disabled, Toast.LENGTH_SHORT).show()
        }
        alignSlider(binding.tilUsername, binding.usernameSlider)
        alignSlider(binding.tilPassword, binding.passwordSlider)
        binding.btnLogin.setOnClickListener {
            hideKeyboard()
            performLogin()
        }
        binding.etPassword.setOnEditorActionListener { _, _, _ ->
            hideKeyboard()
            performLogin()
            true
        }
        binding.accounts.btnClose.setOnClickListener { showPage(Page.LOGIN) }
        binding.accounts.btnGroupLogin.setOnClickListener {
            val users = selectedAccounts.mapNotNull(userManager::getUser)
            if (users.size >= 2) authenticateAccounts(users)
        }
        binding.accounts.btnAddLocal.setOnClickListener {
            if (userManager.getLocalDeviceId() != null) showEditor(null) else requestDeviceAccess()
        }
        binding.accounts.btnAddUser.setOnClickListener {
            accountScanner.launch(ScanOptions()
                .setCaptureActivity(ScanActivity::class.java)
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.account_scan_prompt))
                .addExtra(Intents.Scan.SHOW_MISSING_CAMERA_PERMISSION_DIALOG, true)
                .addExtra(Intents.Scan.MISSING_CAMERA_PERMISSION_DIALOG_MESSAGE, getString(R.string.camera_permission)))
        }
        binding.editor.btnCancelEdit.setOnClickListener { showAccounts() }
        binding.editor.btnSave.setOnClickListener { saveAccount() }
        binding.share.btnBack.setOnClickListener { showAccounts() }
        binding.removal.btnKeepAccount.setOnClickListener { showAccounts() }
        binding.removal.btnRemove.setOnClickListener {
            currentAccount?.let { user ->
                userManager.deleteUser(user.username)
                if (selectedUser?.username == user.username) {
                    selectedUser = null
                    binding.etUsername.text?.clear()
                    binding.etPassword.text?.clear()
                    binding.passwordField.visibility = View.VISIBLE
                }
            }
            showAccounts()
        }
    }

    private fun showPage(target: Page) {
        if (page != target) authentication?.cancel()
        page = target
        if (target != Page.EDITOR) {
            validation?.cancel()
            validation = null
        }
        binding.credentials.visibility = if (target == Page.LOGIN) View.VISIBLE else View.GONE
        binding.accounts.root.visibility = if (target == Page.ACCOUNTS) View.VISIBLE else View.GONE
        binding.editor.root.visibility = if (target == Page.EDITOR) View.VISIBLE else View.GONE
        binding.share.root.visibility = if (target == Page.SHARE) View.VISIBLE else View.GONE
        binding.removal.root.visibility = if (target == Page.REMOVE) View.VISIBLE else View.GONE
    }

    private fun showAccounts() {
        showPage(Page.ACCOUNTS)
        val accounts = binding.accounts
        accounts.llUserList.removeAllViews()
        accounts.tvAccountStatus.visibility = View.GONE
        val users = userManager.getAllUsers()
        selectedAccounts.retainAll(users.map { it.username }.toSet())
        updateGroupLogin()
        accounts.btnAddLocal.visibility = if (users.any { it.local }) View.GONE else View.VISIBLE
        if (users.isEmpty()) {
            accounts.llUserList.addView(TextView(this).apply {
                setText(R.string.no_accounts)
                setPadding(0, 32, 0, 32)
                gravity = android.view.Gravity.CENTER
                setTextColor(getColor(R.color.text_secondary))
            })
        }
        users.forEach { user ->
            val item = LayoutInflater.from(this).inflate(R.layout.item_user_management, accounts.llUserList, false)
            item.findViewById<TextView>(R.id.tvUsername).text = user.username
            item.findViewById<TextView>(R.id.tvRemark).apply {
                text = user.remark
                visibility = if (user.remark.isNotEmpty()) View.VISIBLE else View.GONE
            }
            item.findViewById<View>(R.id.btnShare).apply {
                visibility = if (user.local && user.deviceId == userManager.getLocalDeviceId() &&
                    userManager.getCachedSession(user.username) != null) View.VISIBLE else View.GONE
                setOnClickListener { showAccountCode(user) }
            }
            val selection = item.findViewById<CheckBox>(R.id.selectAccount)
            selection.isChecked = user.username in selectedAccounts
            selection.contentDescription = getString(R.string.select_named_account, user.displayName)
            selection.setOnCheckedChangeListener { _, checked ->
                if (checked) selectedAccounts.add(user.username) else selectedAccounts.remove(user.username)
                updateGroupLogin()
            }
            item.findViewById<View>(R.id.btnEdit).setOnClickListener { showEditor(user) }
            item.findViewById<View>(R.id.btnDelete).setOnClickListener { showRemoval(user) }
            item.setOnClickListener {
                if (selectedAccounts.isNotEmpty()) selection.performClick() else {
                    fillAccount(user)
                    showPage(Page.LOGIN)
                    performLogin()
                }
            }
            accounts.llUserList.addView(item)
        }
    }

    private fun updateGroupLogin() {
        binding.accounts.btnGroupLogin.apply {
            visibility = if (selectedAccounts.size >= 2) View.VISIBLE else View.GONE
            text = getString(R.string.group_login_count, selectedAccounts.size)
        }
    }

    private fun showSplashAd() {
        binding.adScreen.visibility = View.VISIBLE
        binding.adScreen.setOnClickListener { openCampusApp() }
        binding.btnOpenCampus.setOnClickListener { openCampusApp() }
        getSystemService(SensorManager::class.java)?.let { sensors ->
            sensors.registerListener(shakeListener, sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_GAME)
        }
        adCountdown = lifecycleScope.launch {
            for (remaining in AD_SECONDS downTo 1) {
                binding.btnOpenCampus.text = getString(R.string.ad_open, remaining)
                delay(1000)
            }
            dismissSplashAd()
        }
    }

    private fun dismissSplashAd() {
        adCountdown?.cancel()
        adCountdown = null
        getSystemService(SensorManager::class.java)?.unregisterListener(shakeListener)
        binding.adScreen.visibility = View.GONE
    }

    private fun openCampusApp() {
        val intent = packageManager.getLaunchIntentForPackage(CAMPUS_PACKAGE)
        try {
            if (intent == null) throw ActivityNotFoundException()
            dismissSplashAd()
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.ad_missing, Toast.LENGTH_SHORT).show()
        }
    }

    private val shakeListener = object : SensorEventListener {
        private var lastShake = 0L

        override fun onSensorChanged(event: SensorEvent) {
            val (x, y, z) = event.values
            if (sqrt(x * x + y * y + z * z) < SHAKE_FORCE) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastShake < SHAKE_INTERVAL) return
            lastShake = now
            openCampusApp()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun showEditor(user: User?) {
        currentAccount = user
        showPage(Page.EDITOR)
        val editor = binding.editor
        editor.tvDialogTitle.setText(if (user == null) R.string.add_local_account else R.string.edit_account)
        editor.etAccountUsername.setText(user?.username.orEmpty())
        editor.etAccountUsername.isEnabled = user == null
        editor.etAccountPassword.setText(user?.takeIf { it.local }?.password.orEmpty())
        editor.tilAccountPassword.visibility = if (user == null || user.local) View.VISIBLE else View.GONE
        editor.etRemark.setText(user?.remark.orEmpty())
        editor.tilAccountUsername.error = null
        editor.tilAccountPassword.error = null
        editor.tvEditStatus.visibility = if (user == null) View.VISIBLE else View.GONE
        editor.tvEditStatus.setText(R.string.device_detected)
        editor.tvEditStatus.setTextColor(getColor(R.color.text_secondary))
        editor.btnSave.setText(if (user == null) R.string.add_local_account else R.string.save)
    }

    private fun saveAccount() {
        hideKeyboard()
        val editor = binding.editor
        val existing = currentAccount
        val username = editor.etAccountUsername.text?.toString()?.trim().orEmpty()
        val password = if (existing != null && !existing.local) existing.password
            else editor.etAccountPassword.text?.toString().orEmpty()
        val remark = editor.etRemark.text?.toString()?.trim().orEmpty()
        if (username.isEmpty()) {
            editor.tilAccountUsername.error = getString(R.string.student_id_required)
            return
        }
        editor.tilAccountUsername.error = null
        if (password.isEmpty()) {
            editor.tilAccountPassword.error = getString(R.string.password_required)
            return
        }
        editor.tilAccountPassword.error = null
        val user = existing?.copy(password = password, remark = remark)
            ?: User(username, password, remark, requireNotNull(userManager.getLocalDeviceId()), local = true)
        setEditorBusy(true)
        validation = lifecycleScope.launch {
            try {
                if (existing == null) {
                    NbuApiService(username, userManager, user.deviceId).login(password) { message ->
                        requestVerificationCode(message, editor.root, binding.verification)
                    }.getOrThrow()
                } else if (existing.password != password) userManager.clearAuthentication(username)
                userManager.saveUser(user)
                if (existing != null) fillAccount(user)
                validation = null
                showAccounts()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                editor.tvEditStatus.text = e.message ?: getString(R.string.account_failed)
                editor.tvEditStatus.setTextColor(getColor(R.color.status_error))
                editor.tvEditStatus.visibility = View.VISIBLE
            } finally {
                setEditorBusy(false)
                if (page != Page.EDITOR) binding.editor.root.visibility = View.GONE
            }
        }
    }

    private fun setEditorBusy(busy: Boolean) {
        val editor = binding.editor
        editor.btnSave.isEnabled = !busy
        editor.etAccountUsername.isEnabled = !busy && currentAccount == null
        editor.etAccountPassword.isEnabled = !busy
        editor.etRemark.isEnabled = !busy
        if (busy) editor.tvEditStatus.visibility = View.GONE
        editor.btnSave.setText(if (busy && currentAccount == null) R.string.verifying_account
            else if (currentAccount == null) R.string.add_local_account else R.string.save)
    }

    private fun showAccountCode(user: User) {
        if (!user.local || user.deviceId != userManager.getLocalDeviceId() ||
            userManager.getCachedSession(user.username) == null) {
            showAccounts()
            return
        }
        currentAccount = user
        binding.share.tvAccountName.text = user.displayName
        binding.share.ivCode.setImageBitmap(BarcodeEncoder().encodeBitmap(AccountCode.encode(user), BarcodeFormat.QR_CODE, 768, 768))
        showPage(Page.SHARE)
    }

    private fun showRemoval(user: User) {
        currentAccount = user
        binding.removal.tvRemovalMessage.text = getString(R.string.remove_account_message, user.displayName)
        showPage(Page.REMOVE)
    }

    private fun requestDeviceAccess() {
        setLoadingState(true, getString(R.string.reading_device))
        if (ContextCompat.checkSelfPermission(this, photoPermission) == PackageManager.PERMISSION_GRANTED) readLocalDevice()
        else photoLauncher.launch(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            arrayOf(photoPermission, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) else arrayOf(photoPermission))
    }

    private fun readLocalDevice() {
        lifecycleScope.launch {
            try {
                val deviceId = withContext(Dispatchers.IO) { userManager.readDeviceId() }
                if (deviceId == null) showError(getString(R.string.device_missing)) else showEditor(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e.message ?: getString(R.string.device_read_failed))
            } finally {
                setLoadingState(false)
            }
        }
    }

    private fun alignSlider(field: TextInputLayout, slider: View) {
        val container = slider.parent as ViewGroup
        val bounds = Rect()
        container.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val edit = field.editText ?: return@addOnLayoutChangeListener
            edit.getDrawingRect(bounds)
            container.offsetDescendantRectToMyCoords(edit, bounds)
            slider.y = bounds.exactCenterY() - slider.height / 2f
        }
    }

    private fun performLogin() {
        if (!binding.btnLogin.isEnabled) return
        val username = binding.etUsername.text?.toString()?.trim().orEmpty()
        val password = selectedUser?.takeIf { it.username == username }?.password ?: binding.etPassword.text?.toString().orEmpty()
        if (username.isEmpty()) {
            binding.tilUsername.error = getString(R.string.student_id_required)
            return
        }
        binding.tilUsername.error = null
        val existing = userManager.getUser(username)
        if (existing?.deviceId == null) {
            openCampusApp()
            return
        }
        if (password.isEmpty()) {
            binding.tilPassword.error = getString(R.string.password_required)
            return
        }
        binding.tilPassword.error = null
        authenticateAccounts(listOf(existing.copy(password = password)))
    }

    private fun authenticateAccounts(users: List<User>) {
        if (authentication?.isActive == true) return
        val sourcePage = page
        val content = if (page == Page.ACCOUNTS) binding.accounts.root else binding.credentials
        setLoadingState(true, getString(R.string.logging_in))
        authentication = lifecycleScope.launch {
            try {
                val failures = mutableListOf<String>()
                users.forEachIndexed { index, user ->
                    if (users.size > 1) setLoadingState(true,
                        getString(R.string.group_authenticating, user.displayName, index + 1, users.size))
                    val result = NbuApiService(user.username, userManager).login(user.password) { message ->
                        requestVerificationCode("${user.displayName}\n$message", content, binding.verification)
                    }
                    result.onFailure { failures.add("${user.displayName}：${it.message}") }
                }
                if (failures.isNotEmpty()) {
                    showError(failures.joinToString("\n"))
                } else {
                    if (users.size == 1) userManager.saveLastLogin(users.first().username, users.first().password)
                    binding.tvStatus.visibility = View.GONE
                    binding.accounts.tvAccountStatus.visibility = View.GONE
                    Toast.makeText(this@LoginActivity, R.string.logged_in, Toast.LENGTH_SHORT).show()
                    signActivityLauncher.launch(Intent(this@LoginActivity, SignActivity::class.java)
                        .putStringArrayListExtra(SignActivity.EXTRA_USERNAMES, ArrayList(users.map { it.username })))
                }
            } catch (e: CancellationException) {
                binding.tvStatus.visibility = View.GONE
                binding.accounts.tvAccountStatus.visibility = View.GONE
                throw e
            } catch (e: Exception) {
                showError(e.message ?: getString(R.string.login_failed))
            } finally {
                authentication = null
                setLoadingState(false)
                if (page != sourcePage) content.visibility = View.GONE
            }
        }
    }

    private fun setLoadingState(busy: Boolean, message: String = "") {
        binding.btnLogin.isEnabled = !busy
        binding.etUsername.isEnabled = !busy
        binding.etPassword.isEnabled = !busy
        binding.usernameSlider.isEnabled = !busy
        binding.passwordSlider.isEnabled = !busy
        binding.accounts.btnAddLocal.isEnabled = !busy
        binding.accounts.btnAddUser.isEnabled = !busy
        binding.accounts.btnGroupLogin.isEnabled = !busy
        binding.accounts.llUserList.children.forEach { item ->
            item.isEnabled = !busy
            for (id in listOf(R.id.selectAccount, R.id.btnShare, R.id.btnEdit, R.id.btnDelete)) {
                item.findViewById<View>(id)?.isEnabled = !busy
            }
        }
        binding.progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) {
            val status = if (page == Page.ACCOUNTS) binding.accounts.tvAccountStatus else binding.tvStatus
            status.text = message
            status.setTextColor(getColor(R.color.text_secondary))
            status.visibility = View.VISIBLE
        }
    }

    private fun showError(message: String) {
        val status = if (page == Page.ACCOUNTS) binding.accounts.tvAccountStatus else binding.tvStatus
        status.text = message
        status.setTextColor(getColor(R.color.status_error))
        status.visibility = View.VISIBLE
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    private fun fillAccount(user: User) {
        binding.etUsername.setText(user.username)
        binding.etPassword.setText(if (user.local) user.password else "")
        binding.passwordField.visibility = if (user.local) View.VISIBLE else View.GONE
        selectedUser = user.takeUnless { it.local }
    }

    private fun loadLastLogin() {
        val username = userManager.getLastLogin()?.first ?: return
        val user = userManager.getUser(username)
        if (user != null) fillAccount(user) else binding.etUsername.setText(username)
    }
}
