package com.example.bankingapp.features.transactions

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.bankingapp.MainActivity
import com.example.bankingapp.R
import com.example.bankingapp.common.model.TransactionStatus
import com.example.bankingapp.common.utils.FormatAndSecurityUtils
import com.google.android.material.button.MaterialButton

class TransactionResultActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TX_ID = "extra_tx_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_transaction_result)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val txId = intent.getStringExtra(EXTRA_TX_ID)
        val tx = txId?.let { DemoSessionProvider.transactionService.getTransactionById(it) }
        if (tx == null) {
            finish()
            return
        }

        val tvStatusBadge = findViewById<TextView>(R.id.tvResultStatusBadge)
        val tvAmount = findViewById<TextView>(R.id.tvResultAmount)
        val tvFailureReason = findViewById<TextView>(R.id.tvFailureReason)
        val tvReceiptDetails = findViewById<TextView>(R.id.tvReceiptDetails)
        val tvVerificationBadges = findViewById<TextView>(R.id.tvVerificationBadges)
        val tvUpdatedBalance = findViewById<TextView>(R.id.tvUpdatedAccountBalance)

        tvAmount.text = FormatAndSecurityUtils.formatVnd(tx.amount)

        if (tx.status == TransactionStatus.COMPLETED) {
            tvStatusBadge.text = "GIAO DỊCH THÀNH CÔNG (COMPLETED)"
            tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_success)
            tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_success_text))
            tvFailureReason.visibility = View.GONE
        } else {
            tvStatusBadge.text = "GIAO DỊCH THẤT BẠI (${tx.status.name})"
            tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_error)
            tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_error_text))
            val reason = DemoSessionProvider.transactionService.getFailureReason(tx.transactionId)
                ?: "Không đủ điều kiện xác thực hoặc số dư"
            tvFailureReason.text = "Lý do thất bại (Số dư không thay đổi): $reason"
            tvFailureReason.visibility = View.VISIBLE
        }

        val repo = DemoSessionProvider.accountRepository
        val srcAcc = tx.sourceAccountId?.let { repo.getAccountById(it) }
        val targetAcc = tx.targetAccountId?.let { repo.getAccountById(it) }

        tvReceiptDetails.text = buildString {
            append("Mã giao dịch: ${tx.transactionId}\n")
            append("Loại giao dịch: ${tx.transactionType.name}\n")
            if (srcAcc != null) {
                append("Tài khoản nguồn: ${srcAcc.accountType.name} (${srcAcc.accountNumber})\n")
            }
            if (!tx.targetAccountNumber.isNullOrBlank()) {
                val bankLabel = tx.targetBankCode?.let { ExternalBankDirectory.getBankName(it) } ?: "Nội bộ BankingApp"
                append("Tài khoản đích: ${tx.targetAccountNumber} — $bankLabel\n")
            }
            if (!tx.receiverName.isNullOrBlank()) {
                append("Người thụ hưởng: ${tx.receiverName}\n")
            }
            append("Nội dung: ${tx.description}\n")
            append("Thời gian tạo: ${FormatAndSecurityUtils.formatTimestamp(tx.createdAt)}\n")
            tx.completedAt?.let {
                append("Thời gian xử lý: ${FormatAndSecurityUtils.formatTimestamp(it)}")
            }
        }

        val otpText = if (tx.is2faVerified) "OTP 2FA: Đã xác thực" else "OTP 2FA: Chưa xác thực"
        val bioText = when {
            !tx.isHighValue -> "Sinh trắc học: Không yêu cầu (< 10tr)"
            tx.isBiometricVerified -> "Sinh trắc học: Đã xác thực"
            else -> "Sinh trắc học: Chưa xác thực"
        }
        tvVerificationBadges.text = "$otpText  |  $bioText"

        val primaryAcc = srcAcc ?: targetAcc
        if (primaryAcc != null) {
            tvUpdatedBalance.text = "Số dư hiện tại (${primaryAcc.accountNumber}): ${FormatAndSecurityUtils.formatVnd(primaryAcc.balance)}"
        } else {
            tvUpdatedBalance.visibility = View.GONE
        }

        findViewById<MaterialButton>(R.id.btnViewHistory).setOnClickListener {
            val historyIntent = Intent(this, TransactionHistoryActivity::class.java).apply {
                putExtra(TransactionHistoryActivity.EXTRA_FROM_RESULT, true)
            }
            startActivity(historyIntent)
            finish()
        }

        findViewById<MaterialButton>(R.id.btnBackHome).setOnClickListener {
            navigateBackToHome()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                navigateBackToHome()
            }
        })
    }

    private fun navigateBackToHome() {
        val homeIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(homeIntent)
        finish()
    }
}

