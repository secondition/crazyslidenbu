package com.example.nbu.util

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.nbu.R
import com.example.nbu.databinding.ViewVerificationBinding
import com.example.nbu.databinding.DialogVerificationBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

suspend fun requestVerificationCode(
    message: String,
    content: View,
    verification: ViewVerificationBinding
): String? = withContext(Dispatchers.Main) {
    content.visibility = View.GONE
    verification.root.visibility = View.VISIBLE
    verification.tvMessage.text = message
    verification.input.root.error = null
    verification.input.etCode.text?.clear()
    verification.input.etCode.requestFocus()
    try {
        suspendCancellableCoroutine { continuation ->
            fun submit() {
                val code = verification.input.etCode.text?.toString()?.trim().orEmpty()
                if (code.isEmpty()) {
                    verification.input.root.error = verification.root.context.getString(R.string.verification_required)
                } else if (continuation.isActive) {
                    continuation.resume(code)
                }
            }
            verification.btnVerify.setOnClickListener { submit() }
            verification.input.etCode.setOnEditorActionListener { _, _, _ -> submit(); true }
            verification.btnCancel.setOnClickListener {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    } finally {
        verification.btnVerify.setOnClickListener(null)
        verification.btnCancel.setOnClickListener(null)
        verification.input.etCode.setOnEditorActionListener(null)
        verification.input.etCode.text?.clear()
        verification.root.visibility = View.GONE
        content.visibility = View.VISIBLE
    }
}

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
