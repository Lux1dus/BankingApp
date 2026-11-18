package com.example.bankingapp

import com.example.bankingapp.common.model.Transaction
import com.example.bankingapp.common.model.TransactionStatus
import com.example.bankingapp.common.model.TransactionType
import com.example.bankingapp.features.security.EkycStatus
import com.example.bankingapp.features.security.OtpChannel
import com.example.bankingapp.features.security.OtpStatus
import com.example.bankingapp.features.security.OtpVerifyResult
import com.example.bankingapp.features.security.SecurityService
import com.example.bankingapp.features.transactions.MockAccountRepository
import com.example.bankingapp.features.transactions.TransactionService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

class Member2SecurityTransactionTest {

    private lateinit var accountRepo: MockAccountRepository
    private lateinit var securityService: SecurityService
    private lateinit var txService: TransactionService

    @Before
    fun setUp() {
        accountRepo = MockAccountRepository()
        securityService = SecurityService()
        txService = TransactionService(accountRepo, securityService)
    }

    @Test
    fun ekyc_submitAndOfficerApprove_updatesStatusAndLogsAudit() {
        val ekyc = securityService.submitEkyc(
            userId = "user_cust_01",
            idCard = "079099001234",
            docUrl = "demo://cccd.jpg",
            faceUrl = "demo://face.jpg",
            fullName = "Nguyễn Văn An"
        )
        assertEquals(EkycStatus.PENDING, ekyc.status)
        assertFalse(securityService.isEkycApproved("user_cust_01"))

        val reviewed = securityService.reviewEkyc(
            ekycId = ekyc.ekycId,
            officerId = "user_officer_01",
            approved = true,
            reason = null
        )
        assertTrue(reviewed)
        assertTrue(securityService.isEkycApproved("user_cust_01"))
        assertTrue(securityService.getAuditLogs().any { it.action == "EKYC_REVIEW_APPROVED" })
    }

    @Test
    fun ekyc_officerRejectWithReason_savesRejectionReason() {
        val ekyc = securityService.submitEkyc(
            userId = "user_cust_01",
            idCard = "079099001234",
            docUrl = "demo://cccd.jpg",
            faceUrl = "demo://face.jpg"
        )
        val reviewed = securityService.reviewEkyc(
            ekycId = ekyc.ekycId,
            officerId = "user_officer_01",
            approved = false,
            reason = "Ảnh CCCD bị mờ"
        )
        assertTrue(reviewed)
        val latest = securityService.getLatestEkycForUser("user_cust_01")
        assertNotNull(latest)
        assertEquals(EkycStatus.REJECTED, latest?.status)
        assertEquals("Ảnh CCCD bị mờ", latest?.rejectionReason)
        assertFalse(securityService.isEkycApproved("user_cust_01"))
    }

    @Test
    fun otp_validCode_verifiesSuccessfullyAndHashesOtp() {
        val challenge = securityService.requestOtp("user_cust_01", "TX-001", OtpChannel.SMS)
        val plainOtp = securityService.getDemoOtpForDisplay(challenge.challengeId)
        assertNotNull(plainOtp)
        assertNotEquals(plainOtp, challenge.otpHash)

        val result = securityService.verifyOtpDetailed(challenge.challengeId, plainOtp!!)
        assertEquals(OtpVerifyResult.SUCCESS, result)

        val updated = securityService.getChallengeById(challenge.challengeId)
        assertEquals(OtpStatus.VERIFIED, updated?.status)
        assertTrue(updated?.isUsed == true)
    }

    @Test
    fun otp_wrongCodeThreeTimes_locksChallengeWithMaxAttempts() {
        val challenge = securityService.requestOtp("user_cust_01", "TX-002", OtpChannel.SMS)
        val plainOtp = securityService.getDemoOtpForDisplay(challenge.challengeId)!!
        val wrongOtp = if (plainOtp == "000000") "111111" else "000000"

        assertEquals(OtpVerifyResult.WRONG_CODE, securityService.verifyOtpDetailed(challenge.challengeId, wrongOtp))
        assertEquals(OtpVerifyResult.WRONG_CODE, securityService.verifyOtpDetailed(challenge.challengeId, wrongOtp))
        assertEquals(OtpVerifyResult.MAX_ATTEMPTS_EXCEEDED, securityService.verifyOtpDetailed(challenge.challengeId, wrongOtp))

        // Even the correct code must be rejected after lock
        assertEquals(OtpVerifyResult.MAX_ATTEMPTS_EXCEEDED, securityService.verifyOtpDetailed(challenge.challengeId, plainOtp))
        assertEquals(OtpStatus.FAILED_MAX_ATTEMPTS, securityService.getChallengeById(challenge.challengeId)?.status)
    }

    @Test
    fun otp_expiredChallenge_isRejected() {
        val startMs = 1_000_000L
        val challenge = securityService.requestOtp("user_cust_01", "TX-003", OtpChannel.SMS, now = startMs)
        val plainOtp = securityService.getDemoOtpForDisplay(challenge.challengeId)!!

        val expiredNow = startMs + securityService.otpExpiryMs + 1000L
        val result = securityService.verifyOtpDetailed(challenge.challengeId, plainOtp, now = expiredNow)
        assertEquals(OtpVerifyResult.EXPIRED, result)
        assertEquals(OtpStatus.EXPIRED, securityService.getChallengeById(challenge.challengeId)?.status)
    }

    @Test
    fun otp_alreadyUsedChallenge_cannotBeReused() {
        val challenge = securityService.requestOtp("user_cust_01", "TX-004", OtpChannel.SMS)
        val plainOtp = securityService.getDemoOtpForDisplay(challenge.challengeId)!!

        assertEquals(OtpVerifyResult.SUCCESS, securityService.verifyOtpDetailed(challenge.challengeId, plainOtp))
        assertEquals(OtpVerifyResult.ALREADY_USED, securityService.verifyOtpDetailed(challenge.challengeId, plainOtp))
    }

    @Test
    fun validation_negativeOrZeroAmount_isRejected() {
        val zeroTx = Transaction(
            transactionId = "T-ZERO",
            userId = "user_cust_01",
            transactionType = TransactionType.WITHDRAW,
            sourceAccountId = "acc_checking_01",
            amount = BigDecimal.ZERO
        )
        assertFalse(txService.validateTransaction(zeroTx))

        assertThrows(IllegalArgumentException::class.java) {
            txService.processDeposit("acc_checking_01", BigDecimal("-50000"))
        }
    }

    @Test
    fun validation_insufficientBalance_isRejectedAndBalanceUnchanged() {
        val initialBalance = accountRepo.getAccountById("acc_checking_01")!!.balance
        assertThrows(IllegalArgumentException::class.java) {
            txService.processTransfer(
                srcId = "acc_checking_01",
                dstNum = "1001000999888",
                bankCode = null,
                amt = BigDecimal("80000000")
            )
        }
        assertEquals(0, initialBalance.compareTo(accountRepo.getAccountById("acc_checking_01")!!.balance))
    }

    @Test
    fun validation_frozenAccount_isRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            txService.processWithdraw("acc_frozen_03", BigDecimal("100000"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            txService.processTransfer(
                srcId = "acc_checking_01",
                dstNum = "1001000444555",
                bankCode = null,
                amt = BigDecimal("100000")
            )
        }
    }

    @Test
    fun validation_invalidReceiverAccount_isRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            txService.processTransfer(
                srcId = "acc_checking_01",
                dstNum = "999999999999",
                bankCode = null,
                amt = BigDecimal("100000")
            )
        }
    }

    @Test
    fun transaction_normalTransferWithOtp_updatesBothBalancesAtomically() {
        val srcBefore = accountRepo.getAccountById("acc_checking_01")!!.balance
        val dstBefore = accountRepo.getAccountById("acc_checking_02")!!.balance
        val amount = BigDecimal("2000000")

        val pendingTx = txService.processTransfer(
            srcId = "acc_checking_01",
            dstNum = "1001000999888",
            bankCode = null,
            amt = amount
        )
        assertFalse(pendingTx.isHighValue)

        // Before commit, balances must remain unchanged
        assertEquals(0, srcBefore.compareTo(accountRepo.getAccountById("acc_checking_01")!!.balance))

        val challenge = securityService.requestOtp("user_cust_01", pendingTx.transactionId)
        val otpCode = securityService.getDemoOtpForDisplay(challenge.challengeId)!!
        assertTrue(securityService.verifyOtp(challenge.challengeId, otpCode))

        val committed = txService.commitTransaction(pendingTx.transactionId, challenge.challengeId, null)
        assertEquals(TransactionStatus.COMPLETED, committed.status)
        assertTrue(committed.is2faVerified)
        assertEquals(0, srcBefore.subtract(amount).compareTo(accountRepo.getAccountById("acc_checking_01")!!.balance))
        assertEquals(0, dstBefore.add(amount).compareTo(accountRepo.getAccountById("acc_checking_02")!!.balance))
    }

    @Test
    fun transaction_highValueWithoutBiometric_failsCommitAndKeepsBalance() {
        val srcBefore = accountRepo.getAccountById("acc_checking_01")!!.balance
        val pendingTx = txService.processTransfer(
            srcId = "acc_checking_01",
            dstNum = "0071001234567",
            bankCode = "VCB",
            amt = BigDecimal("15000000")
        )
        assertTrue(pendingTx.isHighValue)

        val challenge = securityService.requestOtp("user_cust_01", pendingTx.transactionId)
        val otpCode = securityService.getDemoOtpForDisplay(challenge.challengeId)!!
        assertTrue(securityService.verifyOtp(challenge.challengeId, otpCode))

        val result = txService.commitTransaction(pendingTx.transactionId, challenge.challengeId, bioId = null)
        assertEquals(TransactionStatus.FAILED, result.status)
        assertEquals(0, srcBefore.compareTo(accountRepo.getAccountById("acc_checking_01")!!.balance))
    }

    @Test
    fun transaction_highValueWithOtpAndBiometric_succeedsAndDeductsBalance() {
        val srcBefore = accountRepo.getAccountById("acc_checking_01")!!.balance
        val amount = BigDecimal("15000000")
        val pendingTx = txService.processTransfer(
            srcId = "acc_checking_01",
            dstNum = "0071001234567",
            bankCode = "VCB",
            amt = amount
        )
        assertTrue(pendingTx.isHighValue)

        val challenge = securityService.requestOtp("user_cust_01", pendingTx.transactionId)
        val otpCode = securityService.getDemoOtpForDisplay(challenge.challengeId)!!
        assertTrue(securityService.verifyOtp(challenge.challengeId, otpCode))

        val bio = securityService.verifyBiometrics("user_cust_01", pendingTx.transactionId, passed = true)
        val result = txService.commitTransaction(pendingTx.transactionId, challenge.challengeId, bio.verificationId)

        assertEquals(TransactionStatus.COMPLETED, result.status)
        assertTrue(result.is2faVerified)
        assertTrue(result.isBiometricVerified)
        assertEquals(0, srcBefore.subtract(amount).compareTo(accountRepo.getAccountById("acc_checking_01")!!.balance))
    }

    @Test
    fun auditLog_sensitiveDataIsRedacted_neverLogsPlainOtpOrPassword() {
        val tx = txService.processDeposit("acc_checking_01", BigDecimal("500000"))
        val challenge = securityService.requestOtp("user_cust_01", tx.transactionId)
        val plainOtp = securityService.getDemoOtpForDisplay(challenge.challengeId)!!

        securityService.logEvent(
            actorId = "user_cust_01",
            action = "TEST_SENSITIVE",
            entity = "security_audit_logs",
            entityId = "TEST-1",
            status = "INFO",
            details = "password=SecretPass99 otp=$plainOtp secret=sk_test_12345"
        )

        val allLogs = securityService.getAuditLogs()
        allLogs.forEach { log ->
            assertFalse("Plain OTP leaked into audit log!", log.details.contains(plainOtp))
            assertFalse("Plain password leaked into audit log!", log.details.contains("SecretPass99"))
            assertFalse("Plain secret leaked into audit log!", log.details.contains("sk_test_12345"))
        }
        val testLog = allLogs.first { it.action == "TEST_SENSITIVE" }
        assertTrue(testLog.details.contains("password=[REDACTED]"))
        assertTrue(testLog.details.contains("otp=[REDACTED]"))
        assertTrue(testLog.details.contains("secret=[REDACTED]"))
    }

    @Test
    fun ekyc_blockedWhilePendingOrApproved_andResubmittableAfterRejected() {
        val first = securityService.submitEkyc(
            userId = "user_cust_01",
            idCard = "079099001234",
            docUrl = "demo://cccd1.jpg",
            faceUrl = "demo://face1.jpg"
        )
        assertEquals(1, securityService.getEkycAttemptCount("user_cust_01"))

        // Cannot submit while PENDING
        assertThrows(IllegalStateException::class.java) {
            securityService.submitEkyc("user_cust_01", "079099001234", "demo://cccd2.jpg", "demo://face2.jpg")
        }

        // Officer approves -> cannot submit while APPROVED
        assertTrue(securityService.reviewEkyc(first.ekycId, "user_officer_01", approved = true))
        assertThrows(IllegalStateException::class.java) {
            securityService.submitEkyc("user_cust_01", "079099001234", "demo://cccd2.jpg", "demo://face2.jpg")
        }
    }

    @Test
    fun ekyc_officerCannotReReviewAlreadyDecided_untilCustomerResubmits() {
        val initial = securityService.submitEkyc(
            userId = "user_cust_01",
            idCard = "079099001234",
            docUrl = "demo://cccd1.jpg",
            faceUrl = "demo://face1.jpg"
        )
        assertTrue(securityService.reviewEkyc(initial.ekycId, "user_officer_01", approved = false, reason = "Ảnh mờ"))

        // Officer cannot immediately flip decision without customer resubmitting
        assertFalse(securityService.reviewEkyc(initial.ekycId, "user_officer_01", approved = true))
        assertEquals(EkycStatus.REJECTED, securityService.getLatestEkycForUser("user_cust_01")?.status)

        // Customer resubmits after rejection -> same ekycId returns to PENDING, attempt count = 2
        val resubmitted = securityService.submitEkyc(
            userId = "user_cust_01",
            idCard = "079099001234",
            docUrl = "demo://cccd_clear.jpg",
            faceUrl = "demo://face_clear.jpg"
        )
        assertEquals(initial.ekycId, resubmitted.ekycId)
        assertEquals(EkycStatus.PENDING, resubmitted.status)
        assertEquals(2, securityService.getEkycAttemptCount("user_cust_01"))

        // Officer can now review again and approve
        assertTrue(securityService.reviewEkyc(resubmitted.ekycId, "user_officer_01", approved = true))
        assertTrue(securityService.isEkycApproved("user_cust_01"))
    }

    @Test
    fun transaction_failTransactionMarksFailedAndKeepsBalance() {
        val srcBefore = accountRepo.getAccountById("acc_checking_01")!!.balance
        val pendingTx = txService.processTransfer(
            srcId = "acc_checking_01",
            dstNum = "1001000999888",
            bankCode = null,
            amt = BigDecimal("15000000")
        )
        val failed = txService.failTransaction(pendingTx.transactionId, "Xác thực Sinh trắc học thất bại")
        assertEquals(TransactionStatus.FAILED, failed.status)
        assertEquals("Xác thực Sinh trắc học thất bại", txService.getFailureReason(pendingTx.transactionId))
        assertEquals(0, srcBefore.compareTo(accountRepo.getAccountById("acc_checking_01")!!.balance))
    }
}

