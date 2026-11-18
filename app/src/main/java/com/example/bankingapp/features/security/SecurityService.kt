package com.example.bankingapp.features.security

import com.example.bankingapp.common.utils.FormatAndSecurityUtils
import java.security.SecureRandom
import java.util.UUID

/**
 * Handles Member 2 security workflows: eKYC verification & officer review,
 * OTP/2FA generation & validation, Biometric verification records, and sanitized Audit Logging.
 */
class SecurityService(
    val otpExpiryMs: Long = 120_000L,
    val maxOtpAttempts: Int = 3
) {
    private val ekycMap = LinkedHashMap<String, EkycVerification>()
    private val ekycAttemptCountMap = mutableMapOf<String, Int>()
    private val otpMap = LinkedHashMap<String, OtpChallenge>()
    private val biometricMap = LinkedHashMap<String, BiometricVerification>()
    private val auditLogList = mutableListOf<AuditLog>()
    private val demoPlainOtpMap = mutableMapOf<String, String>()
    private val secureRandom = SecureRandom()

    @Synchronized
    fun submitEkyc(
        userId: String,
        idCard: String,
        docUrl: String,
        faceUrl: String,
        fullName: String = "",
        dob: String = "",
        address: String = ""
    ): EkycVerification {
        val existing = getLatestEkycForUser(userId)
        if (existing != null && (existing.status == EkycStatus.PENDING || existing.status == EkycStatus.APPROVED)) {
            throw IllegalStateException("Không thể gửi yêu cầu mới khi hồ sơ eKYC đang ở trạng thái ${existing.status.name}")
        }

        val now = System.currentTimeMillis()
        val ekycId = existing?.ekycId ?: "EKYC-${UUID.randomUUID().toString().take(8).uppercase()}"
        val verification = EkycVerification(
            ekycId = ekycId,
            userId = userId,
            idCardNumber = idCard.trim(),
            documentPhotoUrl = docUrl.trim(),
            facePhotoUrl = faceUrl.trim(),
            status = EkycStatus.PENDING,
            reviewedBy = null,
            reviewedAt = null,
            rejectionReason = null,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            fullName = fullName.trim(),
            dateOfBirth = dob.trim(),
            address = address.trim()
        )
        ekycMap[ekycId] = verification
        val attempt = (ekycAttemptCountMap[userId] ?: 0) + 1
        ekycAttemptCountMap[userId] = attempt

        val maskedId = if (idCard.length >= 4) "****${idCard.takeLast(4)}" else "****"
        logEvent(
            actorId = userId,
            action = "EKYC_SUBMITTED",
            entity = "ekyc_verifications",
            entityId = ekycId,
            status = "PENDING",
            details = "Khách hàng gửi hồ sơ eKYC lần #$attempt (CCCD: $maskedId)"
        )
        return verification
    }

    @Synchronized
    fun seedEkyc(verification: EkycVerification) {
        ekycMap[verification.ekycId] = verification
        if ((ekycAttemptCountMap[verification.userId] ?: 0) == 0) {
            ekycAttemptCountMap[verification.userId] = 1
        }
    }

    @Synchronized
    fun getEkycAttemptCount(userId: String): Int = ekycAttemptCountMap[userId] ?: 0

    @Synchronized
    fun reviewEkyc(
        ekycId: String,
        officerId: String,
        approved: Boolean,
        reason: String? = null
    ): Boolean {
        val existing = ekycMap[ekycId] ?: return false
        if (existing.status != EkycStatus.PENDING) return false
        val now = System.currentTimeMillis()
        val newStatus = if (approved) EkycStatus.APPROVED else EkycStatus.REJECTED
        val normalizedReason = if (approved) null else (reason?.takeIf { it.isNotBlank() } ?: "Hồ sơ không đạt yêu cầu định danh")
        val updated = existing.copy(
            status = newStatus,
            reviewedBy = officerId,
            reviewedAt = now,
            rejectionReason = normalizedReason,
            updatedAt = now
        )
        ekycMap[ekycId] = updated

        val action = if (approved) "EKYC_REVIEW_APPROVED" else "EKYC_REVIEW_REJECTED"
        val detailMsg = if (approved) {
            "Bank Officer ($officerId) đã duyệt hồ sơ eKYC của ${existing.userId}"
        } else {
            "Bank Officer ($officerId) từ chối hồ sơ eKYC. Lý do: $normalizedReason"
        }
        logEvent(
            actorId = officerId,
            action = action,
            entity = "ekyc_verifications",
            entityId = ekycId,
            status = newStatus.name,
            details = detailMsg
        )
        return true
    }

    @Synchronized
    fun isEkycApproved(userId: String): Boolean {
        return ekycMap.values
            .filter { it.userId == userId }
            .maxByOrNull { it.updatedAt }
            ?.isApproved() == true
    }

    @Synchronized
    fun getAllEkycList(): List<EkycVerification> {
        return ekycMap.values.sortedByDescending { it.updatedAt }
    }

    @Synchronized
    fun getLatestEkycForUser(userId: String): EkycVerification? {
        return ekycMap.values
            .filter { it.userId == userId }
            .maxByOrNull { it.updatedAt }
    }

    @Synchronized
    fun requestOtp(
        userId: String,
        txId: String,
        channel: OtpChannel = OtpChannel.SMS,
        now: Long = System.currentTimeMillis()
    ): OtpChallenge {
        val code = (100000 + secureRandom.nextInt(900000)).toString()
        val challengeId = "OTP-${UUID.randomUUID().toString().take(8).uppercase()}"
        val challenge = OtpChallenge(
            challengeId = challengeId,
            transactionId = txId,
            userId = userId,
            otpHash = FormatAndSecurityUtils.sha256(code),
            channel = channel,
            attemptsCount = 0,
            maxAttempts = maxOtpAttempts,
            isUsed = false,
            expiresAt = now + otpExpiryMs,
            status = OtpStatus.PENDING,
            createdAt = now
        )
        otpMap[challengeId] = challenge
        demoPlainOtpMap[challengeId] = code

        // Never log raw OTP value in audit logs
        logEvent(
            actorId = userId,
            action = "OTP_GENERATED",
            entity = "otp_challenges",
            entityId = challengeId,
            status = "PENDING",
            details = "Phát sinh mã OTP xác thực qua ${channel.name} cho giao dịch $txId (hiệu lực ${otpExpiryMs / 1000}s)"
        )
        return challenge
    }

    @Synchronized
    fun getDemoOtpForDisplay(challengeId: String): String? = demoPlainOtpMap[challengeId]

    @Synchronized
    fun getChallengeById(challengeId: String): OtpChallenge? = otpMap[challengeId]

    @Synchronized
    fun forceExpireOtpForDemo(challengeId: String): Boolean {
        val existing = otpMap[challengeId] ?: return false
        otpMap[challengeId] = existing.copy(
            expiresAt = System.currentTimeMillis() - 1000L,
            status = OtpStatus.EXPIRED
        )
        logEvent(
            actorId = existing.userId,
            action = "OTP_DEMO_FORCE_EXPIRED",
            entity = "otp_challenges",
            entityId = challengeId,
            status = "EXPIRED",
            details = "Đặt trạng thái OTP hết hạn để kiểm thử cho giao dịch ${existing.transactionId}"
        )
        return true
    }

    @Synchronized
    fun verifyOtp(challengeId: String, inputOtp: String): Boolean {
        return verifyOtpDetailed(challengeId, inputOtp).isSuccess
    }

    @Synchronized
    fun verifyOtpDetailed(
        challengeId: String,
        inputOtp: String,
        now: Long = System.currentTimeMillis()
    ): OtpVerifyResult {
        val challenge = otpMap[challengeId] ?: return OtpVerifyResult.NOT_FOUND

        if (challenge.isUsed || challenge.status == OtpStatus.VERIFIED) {
            logEvent(
                actorId = challenge.userId,
                action = "OTP_REPLAY_BLOCKED",
                entity = "otp_challenges",
                entityId = challengeId,
                status = "REJECTED",
                details = "Từ chối sử dụng lại mã OTP đã xác thực trước đó cho giao dịch ${challenge.transactionId}"
            )
            return OtpVerifyResult.ALREADY_USED
        }

        if (challenge.status == OtpStatus.FAILED_MAX_ATTEMPTS || challenge.attemptsCount >= challenge.maxAttempts) {
            return OtpVerifyResult.MAX_ATTEMPTS_EXCEEDED
        }

        if (challenge.isExpired(now) || challenge.status == OtpStatus.EXPIRED) {
            otpMap[challengeId] = challenge.copy(status = OtpStatus.EXPIRED)
            logEvent(
                actorId = challenge.userId,
                action = "OTP_EXPIRED",
                entity = "otp_challenges",
                entityId = challengeId,
                status = "EXPIRED",
                details = "Mã OTP hết hạn khi xác thực giao dịch ${challenge.transactionId}"
            )
            return OtpVerifyResult.EXPIRED
        }

        val inputHash = FormatAndSecurityUtils.sha256(inputOtp.trim())
        val nextAttempts = challenge.attemptsCount + 1

        return if (inputHash == challenge.otpHash) {
            otpMap[challengeId] = challenge.copy(
                attemptsCount = nextAttempts,
                isUsed = true,
                status = OtpStatus.VERIFIED
            )
            logEvent(
                actorId = challenge.userId,
                action = "OTP_VERIFIED_SUCCESS",
                entity = "otp_challenges",
                entityId = challengeId,
                status = "VERIFIED",
                details = "Xác thực OTP thành công cho giao dịch ${challenge.transactionId}"
            )
            OtpVerifyResult.SUCCESS
        } else {
            val locked = nextAttempts >= challenge.maxAttempts
            val nextStatus = if (locked) OtpStatus.FAILED_MAX_ATTEMPTS else OtpStatus.PENDING
            otpMap[challengeId] = challenge.copy(
                attemptsCount = nextAttempts,
                status = nextStatus
            )
            logEvent(
                actorId = challenge.userId,
                action = if (locked) "OTP_LOCKED_MAX_ATTEMPTS" else "OTP_INVALID_ATTEMPT",
                entity = "otp_challenges",
                entityId = challengeId,
                status = nextStatus.name,
                details = "Nhập sai OTP lần $nextAttempts/${challenge.maxAttempts} cho giao dịch ${challenge.transactionId}"
            )
            if (locked) OtpVerifyResult.MAX_ATTEMPTS_EXCEEDED else OtpVerifyResult.WRONG_CODE
        }
    }

    @Synchronized
    fun verifyBiometrics(
        userId: String,
        txId: String,
        passed: Boolean,
        authMethod: String = "BIOMETRIC_PROMPT",
        failureReason: String? = null
    ): BiometricVerification {
        val verificationId = "BIO-${UUID.randomUUID().toString().take(8).uppercase()}"
        val record = BiometricVerification(
            verificationId = verificationId,
            transactionId = txId,
            userId = userId,
            authMethod = authMethod,
            isSuccessful = passed,
            verifiedAt = System.currentTimeMillis(),
            failureReason = if (passed) null else (failureReason ?: "Xác thực sinh trắc học thất bại")
        )
        biometricMap[verificationId] = record

        logEvent(
            actorId = userId,
            action = if (passed) "BIOMETRIC_VERIFIED_SUCCESS" else "BIOMETRIC_VERIFIED_FAILED",
            entity = "biometric_verifications",
            entityId = verificationId,
            status = if (passed) "PASSED" else "FAILED",
            details = if (passed) {
                "Xác thực sinh trắc học ($authMethod) thành công cho giao dịch giá trị cao $txId"
            } else {
                "Xác thực sinh trắc học thất bại cho giao dịch $txId: ${record.failureReason}"
            }
        )
        return record
    }

    @Synchronized
    fun getBiometricVerification(bioId: String): BiometricVerification? = biometricMap[bioId]

    @Synchronized
    fun logEvent(
        actorId: String,
        action: String,
        entity: String,
        entityId: String,
        status: String,
        details: String = ""
    ): AuditLog {
        val sanitizedDetails = FormatAndSecurityUtils.sanitizeAuditDetails(details)
        val sanitizedAction = FormatAndSecurityUtils.sanitizeAuditDetails(action)
        val entry = AuditLog(
            logId = "LOG-${UUID.randomUUID().toString().take(8).uppercase()}",
            actorId = actorId,
            action = sanitizedAction,
            entityName = entity,
            entityId = entityId,
            status = status,
            details = sanitizedDetails.ifBlank { "$sanitizedAction [$status]" },
            timestamp = System.currentTimeMillis()
        )
        auditLogList.add(0, entry)
        return entry
    }

    @Synchronized
    fun getAuditLogs(): List<AuditLog> = auditLogList.toList()

    @Synchronized
    fun clearAll() {
        ekycMap.clear()
        ekycAttemptCountMap.clear()
        otpMap.clear()
        biometricMap.clear()
        auditLogList.clear()
        demoPlainOtpMap.clear()
    }
}

