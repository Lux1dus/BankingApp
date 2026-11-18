package com.example.bankingapp.features.transactions

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.bankingapp.R
import com.example.bankingapp.common.model.Transaction
import com.example.bankingapp.common.model.TransactionStatus
import com.example.bankingapp.common.utils.FormatAndSecurityUtils
import com.example.bankingapp.features.security.BiometricAuthHelper
import com.example.bankingapp.features.security.OtpChannel
import com.example.bankingapp.features.security.OtpVerifyResult
import com.google.android.material.button.MaterialButton

class TransactionConfirmActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TX_ID = "extra_tx_id"
    }

    private enum class AuthStage {
        STAGE1_CONFIRM_INFO,
        STAGE2_BIOMETRIC,
        STAGE3_OTP
    }

    private lateinit var tvConfirmHeaderTitle: TextView
    private lateinit var tvStepIndicator: TextView

    // Stage 1 views
    private lateinit var layoutStage1Confirm: LinearLayout
    private lateinit var tvHighValueBadge: TextView
    private lateinit var tvSummaryDetails: TextView
    private lateinit var tvSummaryAmount: TextView
    private lateinit var tvStage1NextStepHint: TextView

    // Stage 2 views (Biometric)
    private lateinit var layoutStage2Biometric: LinearLayout
    private lateinit var tvBioStageAmount: TextView
    private lateinit var tvBiometricStatus: TextView

    // Stage 3 views (OTP)
    private lateinit var layoutStage3Otp: LinearLayout
    private lateinit var tvOtpStageAmount: TextView
    private lateinit var tvDemoOtpBanner: TextView
    private lateinit var tvOtpCountdown: TextView
    private lateinit var tvOtpAttempts: TextView
    private lateinit var etOtpInput: EditText
    private lateinit var tvOtpVerifyStatus: TextView

    private lateinit var currentTx: Transaction
    private var currentStage: AuthStage = AuthStage.STAGE1_CONFIRM_INFO
    private var currentChallengeId: String? = null
    private var currentBioId: String? = null
    private var countDownTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_transaction_confirm)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val txId = intent.getStringExtra(EXTRA_TX_ID)
        val loadedTx = txId?.let { DemoSessionProvider.transactionService.getTransactionById(it) }
        if (loadedTx == null) {
            Toast.makeText(this, "Không tìm thấy thông tin giao dịch", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        currentTx = loadedTx

        tvConfirmHeaderTitle = findViewById(R.id.tvConfirmHeaderTitle)
        tvStepIndicator = findViewById(R.id.tvStepIndicator)
        layoutStage1Confirm = findViewById(R.id.layoutStage1Confirm)
        tvHighValueBadge = findViewById(R.id.tvHighValueBadge)
        tvSummaryDetails = findViewById(R.id.tvSummaryDetails)
        tvSummaryAmount = findViewById(R.id.tvSummaryAmount)
        tvStage1NextStepHint = findViewById(R.id.tvStage1NextStepHint)
        layoutStage2Biometric = findViewById(R.id.layoutStage2Biometric)
        tvBioStageAmount = findViewById(R.id.tvBioStageAmount)
        tvBiometricStatus = findViewById(R.id.tvBiometricStatus)
        layoutStage3Otp = findViewById(R.id.layoutStage3Otp)
        tvOtpStageAmount = findViewById(R.id.tvOtpStageAmount)
        tvDemoOtpBanner = findViewById(R.id.tvDemoOtpBanner)
        tvOtpCountdown = findViewById(R.id.tvOtpCountdown)
        tvOtpAttempts = findViewById(R.id.tvOtpAttempts)
        etOtpInput = findViewById(R.id.etOtpInput)
        tvOtpVerifyStatus = findViewById(R.id.tvOtpVerifyStatus)

        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { handleCancelNavigation() }
        findViewById<MaterialButton>(R.id.btnCancelStep1).setOnClickListener { handleCancelNavigation() }

        bindTransactionSummary()
        setupStage1Actions()
        setupStage2BiometricActions()
        setupStage3OtpActions()

        // Start cleanly at Stage 1 without generating OTP or triggering Biometric yet
        showStage1Confirm()
    }

    private fun handleCancelNavigation() {
        if (currentStage == AuthStage.STAGE1_CONFIRM_INFO) {
            DemoSessionProvider.transactionService.updateTransactionStatus(
                currentTx.transactionId,
                TransactionStatus.CANCELLED
            )
            finish()
        } else {
            val stageName = if (currentStage == AuthStage.STAGE2_BIOMETRIC) "Sinh trắc học" else "OTP 2FA"
            failAndShowResult("Người dùng hủy bỏ ở bước xác thực $stageName")
        }
    }

    private fun bindTransactionSummary() {
        val repo = DemoSessionProvider.accountRepository
        val srcAcc = currentTx.sourceAccountId?.let { repo.getAccountById(it) }
        val amountText = FormatAndSecurityUtils.formatVnd(currentTx.amount)

        val details = buildString {
            append("Mã GD: ${currentTx.transactionId}\n")
            append("Loại: ${currentTx.transactionType.name}\n")
            if (srcAcc != null) {
                append("Từ TK: ${srcAcc.accountType.name} (${srcAcc.accountNumber})\n")
            }
            if (!currentTx.targetAccountNumber.isNullOrBlank()) {
                val bankDisplay = currentTx.targetBankCode?.let { ExternalBankDirectory.getBankName(it) } ?: "Nội bộ"
                append("Đến STK: ${currentTx.targetAccountNumber} ($bankDisplay)\n")
            }
            if (!currentTx.receiverName.isNullOrBlank()) {
                append("Người nhận: ${currentTx.receiverName}\n")
            }
            append("Nội dung: ${currentTx.description}")
        }
        tvSummaryDetails.text = details
        tvSummaryAmount.text = amountText
        tvBioStageAmount.text = "Số tiền: $amountText"
        tvOtpStageAmount.text = "Số tiền giao dịch: $amountText"

        if (currentTx.isHighValue) {
            tvHighValueBadge.visibility = View.VISIBLE
            tvStage1NextStepHint.text = "Lưu ý: Giao dịch >= 10,000,000 VNĐ. Sau khi xác nhận thông tin, bạn sẽ thực hiện lần lượt: Sinh trắc học → Nhập mã OTP."
        } else {
            tvHighValueBadge.visibility = View.GONE
            tvStage1NextStepHint.text = "Sau khi xác nhận thông tin, hệ thống sẽ chuyển sang màn hình nhập mã OTP 2FA để thực hiện giao dịch."
        }
    }

    private fun showStage1Confirm() {
        currentStage = AuthStage.STAGE1_CONFIRM_INFO
        tvConfirmHeaderTitle.text = "Xác nhận Giao dịch"
        tvStepIndicator.text = if (currentTx.isHighValue) {
            "Bước 1/3: Kiểm tra & Xác nhận thông tin"
        } else {
            "Bước 1/2: Kiểm tra & Xác nhận thông tin"
        }
        layoutStage1Confirm.visibility = View.VISIBLE
        layoutStage2Biometric.visibility = View.GONE
        layoutStage3Otp.visibility = View.GONE
    }

    private fun showStage2Biometric() {
        currentStage = AuthStage.STAGE2_BIOMETRIC
        tvConfirmHeaderTitle.text = "Xác thực Sinh trắc học"
        tvStepIndicator.text = "Bước 2/3: Xác thực Sinh trắc học (Trước OTP)"
        layoutStage1Confirm.visibility = View.GONE
        layoutStage2Biometric.visibility = View.VISIBLE
        layoutStage3Otp.visibility = View.GONE
    }

    private fun showStage3Otp() {
        currentStage = AuthStage.STAGE3_OTP
        tvConfirmHeaderTitle.text = "Xác thực OTP / 2FA"
        tvStepIndicator.text = if (currentTx.isHighValue) {
            "Bước 3/3: Nhập OTP hoàn tất giao dịch"
        } else {
            "Bước 2/2: Nhập OTP hoàn tất giao dịch"
        }
        layoutStage1Confirm.visibility = View.GONE
        layoutStage2Biometric.visibility = View.GONE
        layoutStage3Otp.visibility = View.VISIBLE

        etOtpInput.setText("")
        tvOtpVerifyStatus.visibility = View.GONE
        issueNewOtpChallenge()
    }

    private fun setupStage1Actions() {
        findViewById<MaterialButton>(R.id.btnConfirmStep1).setOnClickListener {
            if (currentTx.isHighValue) {
                showStage2Biometric()
            } else {
                showStage3Otp()
            }
        }
    }

    private fun setupStage2BiometricActions() {
        findViewById<MaterialButton>(R.id.btnTriggerBiometric).setOnClickListener {
            BiometricAuthHelper.showBiometricPrompt(
                activity = this,
                amountFormatted = FormatAndSecurityUtils.formatVnd(currentTx.amount)
            ) { passed, method, reason ->
                handleBiometricDecision(passed, method, reason)
            }
        }

        findViewById<MaterialButton>(R.id.btnSimBioPass).setOnClickListener {
            handleBiometricDecision(true, "DEMO_BIOMETRIC_SIMULATOR", null)
        }

        findViewById<MaterialButton>(R.id.btnSimBioFail).setOnClickListener {
            handleBiometricDecision(
                false,
                "DEMO_BIOMETRIC_SIMULATOR",
                "Người dùng từ chối hoặc vân tay không khớp"
            )
        }
    }

    private fun handleBiometricDecision(passed: Boolean, method: String, reason: String?) {
        val bio = DemoSessionProvider.securityService.verifyBiometrics(
            userId = currentTx.userId,
            txId = currentTx.transactionId,
            passed = passed,
            authMethod = method,
            failureReason = reason
        )
        currentBioId = bio.verificationId

        if (bio.isPassed()) {
            tvBiometricStatus.text = "ĐÃ XÁC THỰC SINH TRẮC HỌC (${bio.verificationId})"
            tvBiometricStatus.setBackgroundResource(R.drawable.bg_badge_success)
            tvBiometricStatus.setTextColor(ContextCompat.getColor(this, R.color.status_success_text))
            Toast.makeText(
                this,
                "Xác thực Sinh trắc học thành công! Chuyển sang bước nhập OTP",
                Toast.LENGTH_SHORT
            ).show()
            showStage3Otp()
        } else {
            val failReason = bio.failureReason ?: "Xác thực sinh trắc học thất bại"
            failAndShowResult("Xác thực Sinh trắc học thất bại ($failReason)")
        }
    }

    private fun setupStage3OtpActions() {
        findViewById<MaterialButton>(R.id.btnQuickFillOtp).setOnClickListener {
            val code = currentChallengeId?.let { DemoSessionProvider.securityService.getDemoOtpForDisplay(it) }
            if (code != null) {
                etOtpInput.setText(code)
            }
        }

        findViewById<MaterialButton>(R.id.btnVerifyOtp).setOnClickListener {
            verifyCurrentOtpAndProceed()
        }

        findViewById<MaterialButton>(R.id.btnExpireOtp).setOnClickListener {
            val cid = currentChallengeId ?: return@setOnClickListener
            countDownTimer?.cancel()
            DemoSessionProvider.securityService.forceExpireOtpForDemo(cid)
            tvOtpCountdown.text = "Hết hạn (0s)"
            tvOtpVerifyStatus.text = "Đã đặt OTP hết hạn. Bấm 'Xác nhận OTP' để kiểm chứng báo lỗi hết hạn!"
            tvOtpVerifyStatus.setBackgroundResource(R.drawable.bg_badge_warning)
            tvOtpVerifyStatus.setTextColor(ContextCompat.getColor(this, R.color.status_warning_text))
            tvOtpVerifyStatus.visibility = View.VISIBLE
        }

        findViewById<MaterialButton>(R.id.btnTestReplayOtp).setOnClickListener {
            val cid = currentChallengeId ?: return@setOnClickListener
            val plainOtp = DemoSessionProvider.securityService.getDemoOtpForDisplay(cid) ?: return@setOnClickListener
            etOtpInput.setText(plainOtp)

            // First call marks OTP as used, second call tests replay rejection
            DemoSessionProvider.securityService.verifyOtpDetailed(cid, plainOtp)
            val replayResult = DemoSessionProvider.securityService.verifyOtpDetailed(cid, plainOtp)
            val challenge = DemoSessionProvider.securityService.getChallengeById(cid)
            if (challenge != null) {
                tvOtpAttempts.text = "Số lần thử: ${challenge.attemptsCount}/${challenge.maxAttempts}"
            }
            countDownTimer?.cancel()
            tvOtpVerifyStatus.visibility = View.VISIBLE
            tvOtpVerifyStatus.text = "${replayResult.messageVi}. Hãy bấm 'Gửi lại OTP mới' để tiếp tục."
            tvOtpVerifyStatus.setBackgroundResource(R.drawable.bg_badge_error)
            tvOtpVerifyStatus.setTextColor(ContextCompat.getColor(this, R.color.status_error_text))
        }

        findViewById<MaterialButton>(R.id.btnResendOtp).setOnClickListener {
            issueNewOtpChallenge()
            etOtpInput.setText("")
            tvOtpVerifyStatus.visibility = View.GONE
        }

        findViewById<MaterialButton>(R.id.btnCancelOtpStage).setOnClickListener {
            failAndShowResult("Người dùng từ chối/hủy xác thực OTP")
        }
    }

    private fun issueNewOtpChallenge() {
        countDownTimer?.cancel()
        val challenge = DemoSessionProvider.securityService.requestOtp(
            userId = currentTx.userId,
            txId = currentTx.transactionId,
            channel = OtpChannel.SMS
        )
        currentChallengeId = challenge.challengeId
        val plainOtp = DemoSessionProvider.securityService.getDemoOtpForDisplay(challenge.challengeId) ?: "------"
        tvDemoOtpBanner.text = "[SMS Demo] Mã OTP của bạn là: $plainOtp (Hash SHA-256 lưu bảo mật)"
        tvOtpAttempts.text = "Số lần thử: 0/${challenge.maxAttempts}"

        countDownTimer = object : CountDownTimer(DemoSessionProvider.securityService.otpExpiryMs, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = millisUntilFinished / 1000L
                tvOtpCountdown.text = "Còn lại: ${sec}s"
            }

            override fun onFinish() {
                tvOtpCountdown.text = "Đã hết hạn (0s)"
            }
        }.start()
    }

    private fun verifyCurrentOtpAndProceed() {
        val cid = currentChallengeId ?: return
        val inputCode = etOtpInput.text.toString().trim()
        val result = DemoSessionProvider.securityService.verifyOtpDetailed(cid, inputCode)
        val challenge = DemoSessionProvider.securityService.getChallengeById(cid)
        if (challenge != null) {
            tvOtpAttempts.text = "Số lần thử: ${challenge.attemptsCount}/${challenge.maxAttempts}"
        }

        when (result) {
            OtpVerifyResult.SUCCESS -> {
                countDownTimer?.cancel()
                val committed = DemoSessionProvider.transactionService.commitTransaction(
                    txId = currentTx.transactionId,
                    challengeId = cid,
                    bioId = currentBioId
                )
                openResultScreen(committed.transactionId)
            }

            OtpVerifyResult.MAX_ATTEMPTS_EXCEEDED -> {
                failAndShowResult(result.messageVi)
            }

            else -> {
                tvOtpVerifyStatus.visibility = View.VISIBLE
                tvOtpVerifyStatus.text = result.messageVi
                tvOtpVerifyStatus.setBackgroundResource(R.drawable.bg_badge_error)
                tvOtpVerifyStatus.setTextColor(ContextCompat.getColor(this, R.color.status_error_text))
            }
        }
    }

    private fun failAndShowResult(reason: String) {
        countDownTimer?.cancel()
        val failedTx = DemoSessionProvider.transactionService.failTransaction(
            txId = currentTx.transactionId,
            reason = reason
        )
        openResultScreen(failedTx.transactionId)
    }

    private fun openResultScreen(txId: String) {
        val intent = Intent(this, TransactionResultActivity::class.java).apply {
            putExtra(TransactionResultActivity.EXTRA_TX_ID, txId)
        }
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        countDownTimer?.cancel()
        super.onDestroy()
    }
}
