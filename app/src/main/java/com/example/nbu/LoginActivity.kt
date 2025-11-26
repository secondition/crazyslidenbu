package com.example.nbu

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.nbu.databinding.ActivityLoginBinding
import com.example.nbu.databinding.DialogAddUserBinding
import com.example.nbu.databinding.DialogUserManagementBinding
import com.example.nbu.model.User
import com.example.nbu.network.NbuApiService
import com.example.nbu.util.UserManager
import kotlinx.coroutines.launch

/**
 * 登录界面
 * 
 * 登录流程：
 * 1. 输入/选择用户名密码，点击登录
 * 2. 检查该用户是否有缓存的有效 JSESSIONID
 * 3. 如果有且有效，直接使用缓存
 * 4. 如果没有或已过期/失效，自动通过 UIS 获取新的 JSESSIONID
 * 5. 更新缓存并跳转到签到界面
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val apiService = NbuApiService()
    private lateinit var userManager: UserManager
    private var userAdapter: ArrayAdapter<DropdownItem>? = null

    // 用于接收 SignActivity 返回结果
    private val signActivityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == SignActivity.RESULT_CLEAR_INPUT) {
            // 清除输入框内容
            binding.etUsername.setText("")
            binding.etPassword.setText("")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        userManager = UserManager(this)
        
        setupViews()
        loadUsers()
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
        
        // 用户名下拉选择监听
        (binding.etUsername as? AutoCompleteTextView)?.setOnItemClickListener { _, _, position, _ ->
            val selectedItem = userAdapter?.getItem(position) ?: return@setOnItemClickListener
            
            if (selectedItem is DropdownItem.UserItem) {
                // 选择了一个用户
                val user = selectedItem.user
                binding.etUsername.setText(user.username)
                binding.etPassword.setText(user.password)
            }
        }

        // 管理账户按钮
        binding.btnManageUsers.setOnClickListener {
            showUserManagementDialog()
        }
    }
    
    /**
     * 加载已保存的用户列表到下拉框
     */
    private fun loadUsers() {
        val users = userManager.getAllUsers()
        val items = mutableListOf<DropdownItem>()
        
        // 添加所有用户
        items.addAll(users.map { DropdownItem.UserItem(it) })
        
        userAdapter = UserDropdownAdapter(this, items)
        
        (binding.etUsername as? AutoCompleteTextView)?.setAdapter(userAdapter)
    }
    
    /**
     * 显示添加用户对话框
     */
    private fun showAddUserDialog(existingUser: User? = null) {
        val dialogBinding = DialogAddUserBinding.inflate(LayoutInflater.from(this))
        
        // 如果是编辑模式，填充已有数据
        existingUser?.let {
            dialogBinding.etUsername.setText(it.username)
            dialogBinding.etPassword.setText(it.password)
            dialogBinding.etRemark.setText(it.remark)
            dialogBinding.etUsername.isEnabled = false // 编辑时不能修改学号
            dialogBinding.tvDialogTitle.text = "编辑用户"
        }
        
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
            
            val user = User(username, password, remark)
            userManager.saveUser(user)
            loadUsers()
            
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
                val btnEdit = itemView.findViewById<View>(R.id.btnEdit)
                val btnDelete = itemView.findViewById<View>(R.id.btnDelete)
                
                tvUsername.text = user.username
                if (user.remark.isNotEmpty()) {
                    tvRemark.text = user.remark
                    tvRemark.visibility = View.VISIBLE
                } else {
                    tvRemark.visibility = View.GONE
                }
                
                btnEdit.setOnClickListener {
                    showAddUserDialog(user)
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
                            loadUsers()
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
            showAddUserDialog()
            dialog.dismiss()
        }
        
        dialogBinding.btnClose.setOnClickListener {
            dialog.dismiss()
        }
        
        dialog.show()
    }

    private fun performLogin() {
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

        // 检查是否有该用户的缓存 Session
        val cachedSession = userManager.getCachedSession(username)
        
        if (cachedSession != null) {
            // 有缓存，先验证是否有效
            tryUseCachedSession(username, password, cachedSession)
        } else {
            // 无缓存，直接通过 UIS 登录
            loginViaUis(username, password)
        }
    }
    
    /**
     * 尝试使用缓存的 Session
     */
    private fun tryUseCachedSession(username: String, password: String, cachedSession: String) {
        setLoadingState(true, "正在验证登录状态...")
        
        lifecycleScope.launch {
            val isValid = apiService.validateSession(cachedSession)
            
            if (isValid) {
                // 缓存有效，直接使用
                setLoadingState(false)
                
                Toast.makeText(
                    this@LoginActivity, 
                    "✓ 登录成功（使用缓存Session）", 
                    Toast.LENGTH_SHORT
                ).show()
                
                navigateToSignActivity(cachedSession, username)
            } else {
                // 缓存失效，自动重新登录（用户无感知）
                userManager.clearSession(username)
                
                // 自动通过 UIS 重新获取（不改变 Loading 状态）
                setLoadingState(true, "缓存已失效，正在重新登录...")
                loginViaUisInternal(username, password, isRefresh = true)
            }
        }
    }
    
    /**
     * 通过 UIS 统一身份认证登录
     */
    private fun loginViaUis(username: String, password: String) {
        setLoadingState(true, "正在登录...")
        
        lifecycleScope.launch {
            loginViaUisInternal(username, password, isRefresh = false)
        }
    }
    
    /**
     * 内部方法：通过 UIS 登录
     * @param isRefresh 是否是刷新缓存（用于区分提示信息）
     */
    private suspend fun loginViaUisInternal(username: String, password: String, isRefresh: Boolean = false) {
        val result = apiService.login(username, password)
        
        result.onSuccess { jsessionId ->
            setLoadingState(false)
            
            // 保存 Session 到缓存
            userManager.saveSession(username, jsessionId)
            
            val message = if (isRefresh) {
                "✓ 登录成功（已刷新Session）"
            } else {
                "✓ 登录成功（新Session）"
            }
            Toast.makeText(this@LoginActivity, message, Toast.LENGTH_SHORT).show()
            
            navigateToSignActivity(jsessionId, username)
        }.onFailure { error ->
            setLoadingState(false)
            showError(error.message ?: "登录失败")
        }
    }
    
    private fun navigateToSignActivity(jsessionId: String, username: String) {
        val intent = Intent(this@LoginActivity, SignActivity::class.java)
        intent.putExtra(SignActivity.EXTRA_JSESSION_ID, jsessionId)
        intent.putExtra(SignActivity.EXTRA_USERNAME, username)
        signActivityLauncher.launch(intent)
    }

    private fun setLoadingState(isLoading: Boolean, message: String = "") {
        binding.btnLogin.isEnabled = !isLoading
        binding.etUsername.isEnabled = !isLoading
        binding.etPassword.isEnabled = !isLoading
        
        if (isLoading) {
            binding.progressBar.visibility = View.VISIBLE
            binding.tvStatus.visibility = View.VISIBLE
            binding.tvStatus.text = message
            binding.tvStatus.setTextColor(getColor(android.R.color.darker_gray))
        } else {
            binding.progressBar.visibility = View.GONE
            binding.tvStatus.visibility = View.GONE
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
}

sealed class DropdownItem {
    data class UserItem(val user: User) : DropdownItem() {
        override fun toString(): String = user.displayName
    }
}

class UserDropdownAdapter(context: Context, items: List<DropdownItem>) : 
    ArrayAdapter<DropdownItem>(context, 0, items) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_user_dropdown, parent, false)
        
        val item = getItem(position)
        val tvTitle = view.findViewById<TextView>(R.id.tvTitle)
        
        if (item is DropdownItem.UserItem) {
            tvTitle.text = item.user.displayName
            tvTitle.setTextColor(context.getColor(R.color.text_primary))
        }
        
        return view
    }
}
