package com.example.nbu

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.example.nbu.databinding.ActivityLoginBinding
import com.example.nbu.model.User
import com.example.nbu.network.NbuApiService
import com.example.nbu.util.AccountCode
import com.example.nbu.util.AppTheme
import com.example.nbu.util.StatusBarUtil
import com.example.nbu.util.UserManager
import com.example.nbu.util.requestVerificationCode
import com.google.zxing.BarcodeFormat
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 登录界面
 */
class LoginActivity : AppCompatActivity() {
    private enum class Page { LOGIN, ACCOUNTS, EDITOR, SHARE, REMOVE }

    private lateinit var binding: ActivityLoginBinding
    private lateinit var userManager: UserManager
    private var selectedUser: User? = null
    private var currentAccount: User? = null
    private var page = Page.LOGIN
    private var roundTrips = 0
    private var validation: Job? = null

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
        StatusBarUtil.setStatusBarColor(this, ContextCompat.getColor(this, R.color.brand_primary))
        userManager = UserManager(this)
        setupViews()
        loadLastLogin()
        if (savedInstanceState != null) {
            selectedUser = savedInstanceState.getString("selectedUser")?.let(userManager::getUser)
            binding.passwordField.visibility = if (selectedUser == null) View.VISIBLE else View.GONE
        }
        roundTrips = savedInstanceState?.getInt("roundTrips") ?: 0
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
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("page", page.name)
        outState.putString("account", currentAccount?.username)
        outState.putString("selectedUser", selectedUser?.username)
        outState.putInt("roundTrips", roundTrips)
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
        binding.lampCord.setOnClickListener { cycleTheme() }
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
            item.findViewById<View>(R.id.btnEdit).setOnClickListener { showEditor(user) }
            item.findViewById<View>(R.id.btnDelete).setOnClickListener { showRemoval(user) }
            item.setOnClickListener {
                fillAccount(user)
                showPage(Page.LOGIN)
                performLogin()
            }
            accounts.llUserList.addView(item)
        }
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
                    NbuApiService(username, userManager).login(password) { message ->
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

    private fun cycleTheme() {
        val modes = AppTheme.Mode.entries
        val next = modes[(modes.indexOf(AppTheme.mode(this)) + 1) % modes.size]
        AppTheme.setMode(this, next)
        Toast.makeText(this, getString(when (next) {
            AppTheme.Mode.LIGHT -> R.string.theme_light
            AppTheme.Mode.DARK -> R.string.theme_dark
            AppTheme.Mode.SYSTEM -> R.string.theme_system
        }), Toast.LENGTH_SHORT).show()
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
        if (password.isEmpty()) {
            binding.tilPassword.error = getString(R.string.password_required)
            return
        }
        binding.tilPassword.error = null
        setLoadingState(true, getString(R.string.logging_in))
        lifecycleScope.launch {
            try {
                val existing = userManager.getUser(username)
                val session = NbuApiService(username, userManager).login(password) { message ->
                    requestVerificationCode(message, binding.credentials, binding.verification)
                }.getOrThrow()
                userManager.saveUser(existing?.copy(password = password) ?: User(username, password))
                userManager.saveLastLogin(username, password)
                binding.tvStatus.visibility = View.GONE
                Toast.makeText(this@LoginActivity, R.string.logged_in, Toast.LENGTH_SHORT).show()
                signActivityLauncher.launch(Intent(this@LoginActivity, SignActivity::class.java)
                    .putExtra(SignActivity.EXTRA_JSESSION_ID, session).putExtra(SignActivity.EXTRA_USERNAME, username))
            } catch (e: CancellationException) {
                binding.tvStatus.visibility = View.GONE
                throw e
            } catch (e: Exception) {
                showError(e.message ?: getString(R.string.login_failed))
            } finally {
                setLoadingState(false)
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
