package com.example.nbu.util

import android.view.View
import com.example.nbu.R
import com.example.nbu.databinding.ViewVerificationBinding
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
