package com.example.bankingapp.features.security

enum class EkycStatus {
    PENDING,
    APPROVED,
    REJECTED
}

enum class OtpChannel {
    SMS,
    EMAIL,
    APP_NOTIFICATION
}

enum class OtpStatus {
    PENDING,
    VERIFIED,
    EXPIRED,
    FAILED_MAX_ATTEMPTS
}

enum class OtpVerifyResult(val isSuccess: Boolean, val messageVi: String) {
    SUCCESS(true, "Xác thực OTP thành công"),
    WRONG_CODE(false, "Mã OTP không chính xác"),
    EXPIRED(false, "Mã OTP đã hết hạn (quá 120 giây)"),
    ALREADY_USED(false, "Mã OTP này đã được sử dụng"),
    MAX_ATTEMPTS_EXCEEDED(false, "Đã vượt quá số lần thử tối đa (3 lần). Giao dịch bị hủy"),
    NOT_FOUND(false, "Không tìm thấy phiên xác thực OTP")
}

data class EkycVerification(
    val ekycId: String,
    val userId: String,
    val idCardNumber: String,
    val documentPhotoUrl: String,
    val facePhotoUrl: String,
    val status: EkycStatus = EkycStatus.PENDING,
    val reviewedBy: String? = null,
    val reviewedAt: Long? = null,
    val rejectionReason: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val fullName: String = "",
    val dateOfBirth: String = "",
    val address: String = ""
) {
    fun isApproved(): Boolean = status == EkycStatus.APPROVED
}

data class OtpChallenge(
    val challengeId: String,
    val transactionId: String,
    val userId: String,
    val otpHash: String,
    val channel: OtpChannel = OtpChannel.SMS,
    val attemptsCount: Int = 0,
    val maxAttempts: Int = 3,
    val isUsed: Boolean = false,
    val expiresAt: Long,
    val status: OtpStatus = OtpStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = now >= expiresAt
}

data class BiometricVerification(
    val verificationId: String,
    val transactionId: String,
    val userId: String,
    val authMethod: String,
    val isSuccessful: Boolean,
    val verifiedAt: Long = System.currentTimeMillis(),
    val failureReason: String? = null
) {
    fun isPassed(): Boolean = isSuccessful
}

data class AuditLog(
    val logId: String,
    val actorId: String,
    val action: String,
    val entityName: String,
    val entityId: String,
    val status: String,
    val details: String,
    val ipAddress: String? = "127.0.0.1",
    val timestamp: Long = System.currentTimeMillis()
)

