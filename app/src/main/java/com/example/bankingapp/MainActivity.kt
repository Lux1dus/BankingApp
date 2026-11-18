package com.example.bankingapp

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.bankingapp.common.utils.FormatAndSecurityUtils
import com.example.bankingapp.features.security.AuditLogActivity
import com.example.bankingapp.features.security.EkycActivity
import com.example.bankingapp.features.security.EkycStatus
import com.example.bankingapp.features.security.OfficerEkycReviewActivity
import com.example.bankingapp.features.transactions.DemoSessionProvider
import com.example.bankingapp.features.transactions.DepositWithdrawActivity
import com.example.bankingapp.features.transactions.TransactionHistoryActivity
import com.example.bankingapp.features.transactions.TransferActivity
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private lateinit var tvCustomerName: TextView
    private lateinit var tvEkycBadge: TextView
    private lateinit var tvCheckingBalance: TextView
    private lateinit var tvSavingBalance: TextView
    private lateinit var tvReceiverBalance: TextView
    private lateinit var tvFrozenBalance: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        tvCustomerName = findViewById(R.id.tvCustomerName)
        tvEkycBadge = findViewById(R.id.tvEkycBadge)
        tvCheckingBalance = findViewById(R.id.tvCheckingBalance)
        tvSavingBalance = findViewById(R.id.tvSavingBalance)
        tvReceiverBalance = findViewById(R.id.tvReceiverBalance)
        tvFrozenBalance = findViewById(R.id.tvFrozenBalance)

        findViewById<LinearLayout>(R.id.cardDepositWithdraw).setOnClickListener {
            startActivity(Intent(this, DepositWithdrawActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.cardTransfer).setOnClickListener {
            startActivity(Intent(this, TransferActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.cardHistory).setOnClickListener {
            startActivity(Intent(this, TransactionHistoryActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.cardCustomerEkyc).setOnClickListener {
            startActivity(Intent(this, EkycActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.cardOfficerReview).setOnClickListener {
            startActivity(Intent(this, OfficerEkycReviewActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.cardAuditLogs).setOnClickListener {
            startActivity(Intent(this, AuditLogActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnResetDemo).setOnClickListener {
            DemoSessionProvider.resetDemoData()
            refreshDashboard()
            Toast.makeText(this, "Đã khôi phục toàn bộ dữ liệu mẫu ban đầu", Toast.LENGTH_SHORT).show()
        }

        refreshDashboard()
    }

    override fun onResume() {
        super.onResume()
        refreshDashboard()
    }

    private fun refreshDashboard() {
        val repo = DemoSessionProvider.accountRepository
        val user = repo.getUserById(DemoSessionProvider.CURRENT_CUSTOMER_ID)
        tvCustomerName.text = "Khách hàng: ${user?.fullName ?: "Nguyễn Văn An"}"

        val checking = repo.getAccountById("acc_checking_01")
        val saving = repo.getAccountById("acc_saving_01")
        val receiver = repo.getAccountById("acc_checking_02")
        val frozen = repo.getAccountById("acc_frozen_03")

        if (checking != null) {
            tvCheckingBalance.text = "• Checking (${checking.accountNumber}): ${FormatAndSecurityUtils.formatVnd(checking.balance)}"
        }
        if (saving != null) {
            tvSavingBalance.text = "• Saving (${saving.accountNumber}): ${FormatAndSecurityUtils.formatVnd(saving.balance)}"
        }
        if (receiver != null) {
            tvReceiverBalance.text = "• TK Nhận Mẫu - Trần Thị Bình (${receiver.accountNumber}): ${FormatAndSecurityUtils.formatVnd(receiver.balance)}"
        }
        if (frozen != null) {
            tvFrozenBalance.text = "• TK Bị Khóa Demo (${frozen.accountNumber}): ${FormatAndSecurityUtils.formatVnd(frozen.balance)} [${frozen.status.name}]"
        }

        val latestEkyc = DemoSessionProvider.securityService.getLatestEkycForUser(DemoSessionProvider.CURRENT_CUSTOMER_ID)
        when (latestEkyc?.status) {
            EkycStatus.APPROVED -> {
                tvEkycBadge.text = "eKYC: APPROVED"
                tvEkycBadge.setBackgroundResource(R.drawable.bg_badge_success)
                tvEkycBadge.setTextColor(ContextCompat.getColor(this, R.color.status_success_text))
            }
            EkycStatus.REJECTED -> {
                tvEkycBadge.text = "eKYC: REJECTED"
                tvEkycBadge.setBackgroundResource(R.drawable.bg_badge_error)
                tvEkycBadge.setTextColor(ContextCompat.getColor(this, R.color.status_error_text))
            }
            EkycStatus.PENDING -> {
                tvEkycBadge.text = "eKYC: PENDING"
                tvEkycBadge.setBackgroundResource(R.drawable.bg_badge_warning)
                tvEkycBadge.setTextColor(ContextCompat.getColor(this, R.color.status_warning_text))
            }
            null -> {
                tvEkycBadge.text = "eKYC: CHƯA NỘP"
                tvEkycBadge.setBackgroundResource(R.drawable.bg_badge_info)
                tvEkycBadge.setTextColor(ContextCompat.getColor(this, R.color.status_info_text))
            }
        }
    }
}
