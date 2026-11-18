package com.example.bankingapp.features.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Wraps AndroidX BiometricPrompt for high-value transaction authorization.
 */
object BiometricAuthHelper {

    enum class HardwareStatus {
        READY,
        NONE_ENROLLED,
        UNAVAILABLE
    }

    fun checkHardwareStatus(context: Context): HardwareStatus {
        val manager = BiometricManager.from(context)
        val result = manager.canAuthenticate(BIOMETRIC_STRONG or BIOMETRIC_WEAK)
        return when (result) {
            BiometricManager.BIOMETRIC_SUCCESS -> HardwareStatus.READY
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> HardwareStatus.NONE_ENROLLED
            else -> HardwareStatus.UNAVAILABLE
        }
    }

    fun canAuthenticate(context: Context): Boolean {
        return checkHardwareStatus(context) == HardwareStatus.READY
    }

    fun showBiometricPrompt(
        activity: FragmentActivity,
        amountFormatted: String,
        onResult: (passed: Boolean, method: String, reason: String?) -> Unit
    ) {
        if (!canAuthenticate(activity)) {
            onResult(
                false,
                "BIOMETRIC_PROMPT",
                "Thiết bị/Emulator chưa cài đặt vân tay. Vui lòng dùng nút Giả lập sinh trắc học bên dưới hoặc cài đặt vân tay trong Settings."
            )
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onResult(true, "BIOMETRIC_PROMPT_DEVICE", null)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                onResult(false, "BIOMETRIC_PROMPT_DEVICE", errString.toString())
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                // User can retry within the prompt; do not close yet
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Xác thực giao dịch giá trị cao")
            .setSubtitle("Số tiền: $amountFormatted")
            .setDescription("Quét vân tay hoặc khuôn mặt để phê duyệt giao dịch vượt ngưỡng 10,000,000 VNĐ")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or BIOMETRIC_WEAK)
            .setNegativeButtonText("Hủy bỏ")
            .build()

        prompt.authenticate(promptInfo)
    }
}

