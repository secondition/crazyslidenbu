package com.example.nbu

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.nbu.databinding.ActivityLoginBinding
import com.example.nbu.databinding.DialogAddUserBinding
import com.example.nbu.databinding.DialogAccountCodeBinding
import com.example.nbu.databinding.DialogUserManagementBinding
import com.example.nbu.model.User
import com.example.nbu.network.NbuApiService
import com.example.nbu.util.StatusBarUtil
import com.example.nbu.util.AccountCode
import com.google.zxing.BarcodeFormat
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.example.nbu.util.UserManager
import com.example.nbu.util.requestVerificationCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 登录界面
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var userManager: UserManager
    
    // 用于启动 SignActivity
    private val signActivityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    private val accountScanner = registerForActivityResult(ScanContract()) { result ->
        val value = result.contents ?: return@registerForActivityResult
        val received = try {
            AccountCode.decode(value)
        } catch (e: Exception) {
            showError(getString(R.string.invalid_account_code))
            return@registerForActivityResult
        }
        val existing = userManager.getUser(received.username)
        if (existing != null && (existing.password != received.password || existing.deviceId != received.deviceId)) {
            userManager.clearAuthentication(received.username)
        }
        val user = received.copy(remark = existing?.remark.orEmpty())
        userManager.saveUser(user)
        binding.etUsername.setText(user.username)
        binding.etPassword.setText(user.password)
        binding.tvStatus.visibility = View.GONE
        Toast.makeText(this, R.string.account_added, Toast.LENGTH_SHORT).show()
    }

    private val photoPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES
            else Manifest.permission.READ_EXTERNAL_STORAGE

    private val photoLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[photoPermission] == true) {
            readLocalDevice()
        } else {
            setLoadingState(false)
            showError(getString(R.string.device_permission))
            if (userManager.getAllUsers().isEmpty()) showUserManagementDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // 设置状态栏颜色
        StatusBarUtil.setStatusBarColor(this, ContextCompat.getColor(this, R.color.brand_primary))

        userManager = UserManager(this)
        
        setupViews()
        loadLastLogin()
        val users = userManager.getAllUsers()
        if (savedInstanceState == null && userManager.getLocalDeviceId() == null &&
            (users.isEmpty() || users.any { it.deviceId == null })) {
            requestDeviceAccess()
        }
    }

    private fun requestDeviceAccess() {
        setLoadingState(true, "正在识别本机设备标识...")
        if (ContextCompat.checkSelfPermission(this, photoPermission) == PackageManager.PERMISSION_GRANTED) {
            readLocalDevice()
        } else {
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                arrayOf(photoPermission, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            } else {
                arrayOf(photoPermission)
            }
            photoLauncher.launch(permissions)
        }
    }

    private fun readLocalDevice() {
        lifecycleScope.launch {
            try {
                val deviceId = withContext(Dispatchers.IO) { userManager.readDeviceId() }
                binding.tvStatus.setText(if (deviceId == null) R.string.device_missing else R.string.device_detected)
                if (deviceId == null && userManager.getAllUsers().isEmpty()) showUserManagementDialog()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e.message ?: "读取共享设备标识失败")
            } finally {
                setLoadingState(false)
            }
        }
    }

    private fun setupViews() {
        // 登录按钮
        binding.btnLogin.setOnClickListener {
            hideKeyboard()
            performLogin()
        }

        // 监听密码输入框的完成动作
        binding.etPassword.setOnEditorActionListener { _, _, _ ->
            hideKeyboard()
            performLogin()
            true
        }

        binding.tvTitle.setOnClickListener {
            showUserManagementDialog()
        }
    }
    
    /**
     * 显示编辑用户对话框
     */
    private fun showEditUserDialog(existingUser: User) {
        val dialogBinding = DialogAddUserBinding.inflate(LayoutInflater.from(this))
        
        dialogBinding.etUsername.setText(existingUser.username)
        dialogBinding.etPassword.setText(existingUser.password)
        dialogBinding.etRemark.setText(existingUser.remark)
        dialogBinding.etUsername.isEnabled = false
        
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()
        
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        dialogBinding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }
        
        dialogBinding.btnSave.setOnClickListener {
            val username = dialogBinding.etUsername.text?.toString()?.trim() ?: ""
            val password = dialogBinding.etPassword.text?.toString() ?: ""
            val remark = dialogBinding.etRemark.text?.toString()?.trim() ?: ""
            
            if (username.isEmpty()) {
                dialogBinding.tilUsername.error = "请输入学号"
                return@setOnClickListener
            } else {
                dialogBinding.tilUsername.error = null
            }
            
            if (password.isEmpty()) {
                dialogBinding.tilPassword.error = "请输入密码"
                return@setOnClickListener
            } else {
                dialogBinding.tilPassword.error = null
            }
            
            val user = existingUser.copy(password = password, remark = remark)
            if (existingUser.password != password) userManager.clearAuthentication(username)
            userManager.saveUser(user)
            
            Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show()
            
            // 自动填充到输入框
            binding.etUsername.setText(username)
            binding.etPassword.setText(password)
            
            dialog.dismiss()
        }
        
        dialog.show()
    }
    
    /**
     * 显示用户管理对话框
     */
    private fun showUserManagementDialog() {
        val dialogBinding = DialogUserManagementBinding.inflate(LayoutInflater.from(this))
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()
            
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        fun refreshList() {
            dialogBinding.llUserList.removeAllViews()
            val users = userManager.getAllUsers()
            
            if (users.isEmpty()) {
                val emptyView = TextView(this).apply {
                    text = "暂无保存的用户"
                    setPadding(0, 32, 0, 32)
                    gravity = android.view.Gravity.CENTER
                    setTextColor(getColor(R.color.text_secondary))
                }
                dialogBinding.llUserList.addView(emptyView)
            }
            
            users.forEach { user ->
                val itemView = LayoutInflater.from(this).inflate(R.layout.item_user_management, dialogBinding.llUserList, false)
                val tvUsername = itemView.findViewById<TextView>(R.id.tvUsername)
                val tvRemark = itemView.findViewById<TextView>(R.id.tvRemark)
                val btnShare = itemView.findViewById<View>(R.id.btnShare)
                val btnEdit = itemView.findViewById<View>(R.id.btnEdit)
                val btnDelete = itemView.findViewById<View>(R.id.btnDelete)
                
                tvUsername.text = user.username
                if (user.remark.isNotEmpty()) {
                    tvRemark.text = user.remark
                    tvRemark.visibility = View.VISIBLE
                } else {
                    tvRemark.visibility = View.GONE
                }
                
                btnShare.visibility = if (user.local && user.deviceId == userManager.getLocalDeviceId() &&
                    userManager.getCachedSession(user.username) != null) View.VISIBLE else View.GONE
                btnShare.setOnClickListener { showAccountCode(user) }

                btnEdit.setOnClickListener {
                    showEditUserDialog(user)
                    dialog.dismiss()
                }
                
                btnDelete.setOnClickListener {
                    MaterialAlertDialogBuilder(this, R.style.TransparentDialog)
                        .setTitle("确认删除")
                        .setMessage("确定要删除用户 ${user.displayName} 吗？")
                        .setBackground(resources.getDrawable(R.drawable.bg_dialog_rounded, null))
                        .setPositiveButton("删除") { _, _ ->
                            userManager.deleteUser(user.username)
                            refreshList()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
                
                // 点击整个列表项直接登录该账户
                itemView.setOnClickListener {
                    binding.etUsername.setText(user.username)
                    binding.etPassword.setText(user.password)
                    dialog.dismiss()
                    performLogin()
                }
                
                dialogBinding.llUserList.addView(itemView)
            }
        }
        
        refreshList()
        
        dialogBinding.btnAddUser.setOnClickListener {
            dialog.dismiss()
            accountScanner.launch(ScanOptions()
                .setCaptureActivity(ScanActivity::class.java)
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.account_scan_prompt))
                .addExtra(Intents.Scan.SHOW_MISSING_CAMERA_PERMISSION_DIALOG, true)
                .addExtra(Intents.Scan.MISSING_CAMERA_PERMISSION_DIALOG_MESSAGE, getString(R.string.camera_permission)))
        }
        
        dialogBinding.btnClose.setOnClickListener {
            dialog.dismiss()
        }
        dialog.setOnDismissListener { binding.tvTitle.reset() }

        dialog.show()
    }

    private fun showAccountCode(user: User, onClose: (() -> Unit)? = null) {
        if (!user.local || user.deviceId != userManager.getLocalDeviceId() ||
            userManager.getCachedSession(user.username) == null) return
        val dialogBinding = DialogAccountCodeBinding.inflate(layoutInflater)
        dialogBinding.tvAccountName.text = user.displayName
        val code = AccountCode.encode(user)
        dialogBinding.ivCode.setImageBitmap(BarcodeEncoder().encodeBitmap(code, BarcodeFormat.QR_CODE, 768, 768))
        val dialog = MaterialAlertDialogBuilder(this).setView(dialogBinding.root).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        if (onClose != null) dialogBinding.btnClose.setText(R.string.start_sign)
        dialogBinding.btnClose.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { onClose?.invoke() }
        dialog.show()
    }

    private fun performLogin() {
        if (!binding.btnLogin.isEnabled) return
        val username = binding.etUsername.text?.toString()?.trim() ?: ""
        val password = binding.etPassword.text?.toString() ?: ""

        // 验证输入
        if (username.isEmpty()) {
            binding.tilUsername.error = "请输入学号"
            return
        } else {
            binding.tilUsername.error = null
        }

        if (password.isEmpty()) {
            binding.tilPassword.error = "请输入密码"
            return
        } else {
            binding.tilPassword.error = null
        }
        
        setLoadingState(true, "正在登录...")
        lifecycleScope.launch {
            try {
                val existingUser = userManager.getUser(username)
                val apiService = NbuApiService(username, userManager)
                val session = apiService.login(password, ::requestVerificationCode).getOrThrow()
                val deviceId = userManager.getDeviceId(username)
                val user = User(username, password, existingUser?.remark.orEmpty(), deviceId,
                    local = deviceId != null && deviceId == userManager.getLocalDeviceId() &&
                        (existingUser == null || existingUser.local || existingUser.deviceId == null))
                userManager.saveUser(user)
                userManager.saveLastLogin(username, password)
                binding.tvStatus.visibility = View.GONE
                Toast.makeText(this@LoginActivity, "✓ 登录成功", Toast.LENGTH_SHORT).show()
                if (user.local && existingUser?.local != true) {
                    showAccountCode(user) { navigateToSignActivity(session, username) }
                } else {
                    navigateToSignActivity(session, username)
                }
            } catch (e: CancellationException) {
                binding.tvStatus.visibility = View.GONE
                throw e
            } catch (e: Exception) {
                showError(e.message ?: "登录失败")
            } finally {
                setLoadingState(false)
            }
        }
    }

    private fun navigateToSignActivity(jsessionId: String, username: String) {
        val intent = Intent(this, SignActivity::class.java)
        intent.putExtra(SignActivity.EXTRA_JSESSION_ID, jsessionId)
        intent.putExtra(SignActivity.EXTRA_USERNAME, username)
        signActivityLauncher.launch(intent)
    }

    private fun setLoadingState(isLoading: Boolean, message: String = "") {
        binding.btnLogin.isEnabled = !isLoading
        binding.etUsername.isEnabled = !isLoading
        binding.etPassword.isEnabled = !isLoading
        binding.tvTitle.isEnabled = !isLoading

        if (isLoading) {
            binding.progressBar.visibility = View.VISIBLE
            binding.tvStatus.visibility = View.VISIBLE
            binding.tvStatus.text = message
            binding.tvStatus.setTextColor(getColor(android.R.color.darker_gray))
        } else {
            binding.progressBar.visibility = View.GONE
        }
    }

    private fun showError(message: String) {
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.text = message
        binding.tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let {
            imm.hideSoftInputFromWindow(it.windowToken, 0)
        }
    }
    
    /**
     * 加载上次登录的账号密码
     */
    private fun loadLastLogin() {
        val lastLogin = userManager.getLastLogin()
        if (lastLogin != null) {
            binding.etUsername.setText(lastLogin.first)
            binding.etPassword.setText(lastLogin.second)
        }
    }

}
