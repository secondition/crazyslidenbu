package com.example.nbu.util

import androidx.appcompat.app.AppCompatActivity
import com.example.nbu.databinding.DialogVerificationBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

suspend fun AppCompatActivity.requestVerificationCode(message: String): String? = withContext(Dispatchers.Main) {
    suspendCancellableCoroutine { continuation ->
        val binding = DialogVerificationBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this@requestVerificationCode)
            .setTitle("验证登录设备")
            .setMessage(message)
            .setView(binding.root)
            .setPositiveButton("验证", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = binding.etCode.text?.toString()?.trim().orEmpty()
                if (code.isEmpty()) {
                    binding.root.error = "请输入风华宁大验证码"
                } else if (continuation.isActive) {
                    continuation.resume(code)
                    dialog.dismiss()
                }
            }
        }
        dialog.setOnDismissListener {
            if (continuation.isActive) continuation.resume(null)
        }
        continuation.invokeOnCancellation { window.decorView.post { dialog.dismiss() } }
        dialog.show()
    }
}
