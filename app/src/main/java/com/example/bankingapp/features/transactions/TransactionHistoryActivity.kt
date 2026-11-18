package com.example.bankingapp.features.transactions

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.bankingapp.MainActivity
import com.example.bankingapp.R
import com.example.bankingapp.common.model.Transaction
import com.example.bankingapp.common.model.TransactionStatus
import com.example.bankingapp.common.model.TransactionType
import com.example.bankingapp.common.utils.FormatAndSecurityUtils
import com.example.bankingapp.common.utils.UiFilterHelper
import com.google.android.material.button.MaterialButton

class TransactionHistoryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FROM_RESULT = "extra_from_result"
    }

    private enum class HistoryFilter {
        ALL, CHECKING, SAVING, COMPLETED, OTHER
    }

    private lateinit var containerTransactions: LinearLayout
    private var currentFilter = HistoryFilter.ALL
    private lateinit var filterButtons: List<MaterialButton>
    private var fromResultScreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_transaction_history)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        fromResultScreen = intent.getBooleanExtra(EXTRA_FROM_RESULT, false)
        containerTransactions = findViewById(R.id.containerTransactions)
        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { handleBackNavigation() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackNavigation()
            }
        })

        val btnFilterAll = findViewById<MaterialButton>(R.id.btnFilterAll)
        val btnFilterChecking = findViewById<MaterialButton>(R.id.btnFilterChecking)
        val btnFilterSaving = findViewById<MaterialButton>(R.id.btnFilterSaving)
        val btnFilterCompleted = findViewById<MaterialButton>(R.id.btnFilterCompleted)
        val btnFilterOther = findViewById<MaterialButton>(R.id.btnFilterOther)
        filterButtons = listOf(btnFilterAll, btnFilterChecking, btnFilterSaving, btnFilterCompleted, btnFilterOther)

        btnFilterAll.setOnClickListener {
            currentFilter = HistoryFilter.ALL
            UiFilterHelper.highlightSelectedFilter(this, btnFilterAll, filterButtons, R.color.bank_primary)
            renderList()
        }
        btnFilterChecking.setOnClickListener {
            currentFilter = HistoryFilter.CHECKING
            UiFilterHelper.highlightSelectedFilter(this, btnFilterChecking, filterButtons, R.color.bank_primary)
            renderList()
        }
        btnFilterSaving.setOnClickListener {
            currentFilter = HistoryFilter.SAVING
            UiFilterHelper.highlightSelectedFilter(this, btnFilterSaving, filterButtons, R.color.bank_primary)
            renderList()
        }
        btnFilterCompleted.setOnClickListener {
            currentFilter = HistoryFilter.COMPLETED
            UiFilterHelper.highlightSelectedFilter(this, btnFilterCompleted, filterButtons, R.color.bank_primary)
            renderList()
        }
        btnFilterOther.setOnClickListener {
            currentFilter = HistoryFilter.OTHER
            UiFilterHelper.highlightSelectedFilter(this, btnFilterOther, filterButtons, R.color.bank_primary)
            renderList()
        }

        UiFilterHelper.highlightSelectedFilter(this, btnFilterAll, filterButtons, R.color.bank_primary)
        renderList()
    }

    private fun renderList() {
        containerTransactions.removeAllViews()
        val txService = DemoSessionProvider.transactionService
        val list: List<Transaction> = when (currentFilter) {
            HistoryFilter.ALL -> txService.getAllTransactions()
            HistoryFilter.CHECKING -> txService.getTransactionsByAccount("acc_checking_01")
            HistoryFilter.SAVING -> txService.getTransactionsByAccount("acc_saving_01")
            HistoryFilter.COMPLETED -> txService.getAllTransactions().filter { it.status == TransactionStatus.COMPLETED }
            HistoryFilter.OTHER -> txService.getAllTransactions().filter { it.status != TransactionStatus.COMPLETED }
        }

        if (list.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "Không có giao dịch nào phù hợp với bộ lọc."
                setTextColor(ContextCompat.getColor(this@TransactionHistoryActivity, R.color.bank_text_secondary))
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(16, 48, 16, 48)
            }
            containerTransactions.addView(emptyTv)
            return
        }

        val dp = resources.displayMetrics.density
        list.forEach { tx ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_card_rounded)
                val pad = (14 * dp).toInt()
                setPadding(pad, pad, pad, pad)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (10 * dp).toInt()
                }
            }

            // Top Row: Type + Status Badge
            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val typeTv = TextView(this).apply {
                text = "${tx.transactionType.name} • ${tx.transactionId}"
                setTextColor(ContextCompat.getColor(this@TransactionHistoryActivity, R.color.bank_text_primary))
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val statusTv = TextView(this).apply {
                text = tx.status.name
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
                when (tx.status) {
                    TransactionStatus.COMPLETED -> {
                        setBackgroundResource(R.drawable.bg_badge_success)
                        setTextColor(ContextCompat.getColor(this@TransactionHistoryActivity, R.color.status_success_text))
                    }
                    TransactionStatus.FAILED, TransactionStatus.CANCELLED -> {
                        setBackgroundResource(R.drawable.bg_badge_error)
                        setTextColor(ContextCompat.getColor(this@TransactionHistoryActivity, R.color.status_error_text))
                    }
                    TransactionStatus.REFUNDED -> {
                        setBackgroundResource(R.drawable.bg_badge_purple)
                        setTextColor(ContextCompat.getColor(this@TransactionHistoryActivity, R.color.status_purple_text))
                    }
                    else -> {
                        setBackgroundResource(R.drawable.bg_badge_warning)
                        setTextColor(ContextCompat.getColor(this@TransactionHistoryActivity, R.color.status_warning_text))
                    }
                }
            }
            topRow.addView(typeTv)
            topRow.addView(statusTv)
            card.addView(topRow)

            // Amount
            val sign = if (tx.transactionType == TransactionType.DEPOSIT || tx.transactionType == TransactionType.REFUND) "+" else "-"
            val amountTv = TextView(this).apply {
                text = "$sign${FormatAndSecurityUtils.formatVnd(tx.amount)}"
                textSize = 17f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(
                    ContextCompat.getColor(
                        this@TransactionHistoryActivity,
                        if (sign == "+") R.color.status_success_text else R.color.bank_primary
                    )
                )
                setPadding(0, (6 * dp).toInt(), 0, (4 * dp).toInt())
            }
            card.addView(amountTv)

            // Details
            val detailsStr = buildString {
                if (!tx.targetAccountNumber.isNullOrBlank()) {
                    val bank = tx.targetBankCode ?: "Nội bộ"
                    append("Đích: ${tx.targetAccountNumber} ($bank)")
                    if (!tx.receiverName.isNullOrBlank()) append(" — ${tx.receiverName}")
                    append("\n")
                }
                append("Mô tả: ${tx.description}\n")
                append("Xác thực: 2FA=${if (tx.is2faVerified) "OK" else "Chưa"} | Biometric=${if (tx.isBiometricVerified) "OK" else "Không/Chưa"}\n")
                append("Thời gian: ${FormatAndSecurityUtils.formatTimestamp(tx.createdAt)}")
            }
            val detailsTv = TextView(this).apply {
                text = detailsStr
                setTextColor(ContextCompat.getColor(this@TransactionHistoryActivity, R.color.bank_text_secondary))
                textSize = 12f
            }
            card.addView(detailsTv)

            if (tx.status == TransactionStatus.COMPLETED && tx.transactionType != TransactionType.REFUND) {
                val btnRefund = MaterialButton(
                    this,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = "Hoàn tiền Demo (Refund)"
                    textSize = 11f
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (8 * dp).toInt()
                    }
                    setOnClickListener {
                        val ok = txService.refundTransaction(tx.transactionId, "Kiểm thử hoàn tiền Demo")
                        if (ok) {
                            Toast.makeText(this@TransactionHistoryActivity, "Đã hoàn tiền giao dịch ${tx.transactionId}", Toast.LENGTH_SHORT).show()
                            renderList()
                        } else {
                            Toast.makeText(this@TransactionHistoryActivity, "Không thể hoàn tiền giao dịch này", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                card.addView(btnRefund)
            }

            containerTransactions.addView(card)
        }
    }

    private fun handleBackNavigation() {
        if (fromResultScreen) {
            val homeIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(homeIntent)
        }
        finish()
    }
}

