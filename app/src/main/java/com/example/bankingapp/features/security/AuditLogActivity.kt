package com.example.bankingapp.features.security

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.bankingapp.R
import com.example.bankingapp.common.utils.FormatAndSecurityUtils
import com.example.bankingapp.common.utils.UiFilterHelper
import com.example.bankingapp.features.transactions.DemoSessionProvider
import com.google.android.material.button.MaterialButton

class AuditLogActivity : AppCompatActivity() {

    private enum class LogFilter {
        ALL, EKYC, AUTH, TX
    }

    private lateinit var containerAuditLogs: LinearLayout
    private var currentFilter = LogFilter.ALL
    private lateinit var filterButtons: List<MaterialButton>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_audit_log)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        containerAuditLogs = findViewById(R.id.containerAuditLogs)
        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { finish() }

        val btnFilterAll = findViewById<MaterialButton>(R.id.btnFilterAll)
        val btnFilterEkyc = findViewById<MaterialButton>(R.id.btnFilterEkyc)
        val btnFilterAuth = findViewById<MaterialButton>(R.id.btnFilterAuth)
        val btnFilterTx = findViewById<MaterialButton>(R.id.btnFilterTx)
        filterButtons = listOf(btnFilterAll, btnFilterEkyc, btnFilterAuth, btnFilterTx)

        btnFilterAll.setOnClickListener {
            currentFilter = LogFilter.ALL
            UiFilterHelper.highlightSelectedFilter(this, btnFilterAll, filterButtons, R.color.bank_accent)
            renderLogs()
        }
        btnFilterEkyc.setOnClickListener {
            currentFilter = LogFilter.EKYC
            UiFilterHelper.highlightSelectedFilter(this, btnFilterEkyc, filterButtons, R.color.bank_accent)
            renderLogs()
        }
        btnFilterAuth.setOnClickListener {
            currentFilter = LogFilter.AUTH
            UiFilterHelper.highlightSelectedFilter(this, btnFilterAuth, filterButtons, R.color.bank_accent)
            renderLogs()
        }
        btnFilterTx.setOnClickListener {
            currentFilter = LogFilter.TX
            UiFilterHelper.highlightSelectedFilter(this, btnFilterTx, filterButtons, R.color.bank_accent)
            renderLogs()
        }

        findViewById<MaterialButton>(R.id.btnTestSensitiveFilter).setOnClickListener {
            DemoSessionProvider.securityService.logEvent(
                actorId = DemoSessionProvider.CURRENT_CUSTOMER_ID,
                action = "TEST_SENSITIVE_FILTER",
                entity = "security_audit_logs",
                entityId = "TEST",
                status = "INFO",
                details = "Thử log password=MySecret123 otp=654321 secret=sk_live_999"
            )
            currentFilter = LogFilter.ALL
            UiFilterHelper.highlightSelectedFilter(this, btnFilterAll, filterButtons, R.color.bank_accent)
            renderLogs()
            Toast.makeText(this, "Đã ghi log kiểm thử — OTP/Password/Secret đã tự động bị che [REDACTED]", Toast.LENGTH_LONG).show()
        }

        UiFilterHelper.highlightSelectedFilter(this, btnFilterAll, filterButtons, R.color.bank_accent)
        renderLogs()
    }

    private fun renderLogs() {
        containerAuditLogs.removeAllViews()
        val allLogs = DemoSessionProvider.securityService.getAuditLogs()
        val filtered = when (currentFilter) {
            LogFilter.ALL -> allLogs
            LogFilter.EKYC -> allLogs.filter { it.entityName == "ekyc_verifications" || it.action.startsWith("EKYC") }
            LogFilter.AUTH -> allLogs.filter {
                it.entityName == "otp_challenges" ||
                    it.entityName == "biometric_verifications" ||
                    it.action.startsWith("OTP") ||
                    it.action.startsWith("BIOMETRIC")
            }
            LogFilter.TX -> allLogs.filter { it.entityName == "transactions" || it.action.startsWith("TX") }
        }

        if (filtered.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "Không có nhật ký nào trong mục này."
                setTextColor(ContextCompat.getColor(this@AuditLogActivity, R.color.bank_text_secondary))
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(16, 48, 16, 48)
            }
            containerAuditLogs.addView(emptyTv)
            return
        }

        val dp = resources.displayMetrics.density
        filtered.forEach { log ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_card_rounded)
                val pad = (12 * dp).toInt()
                setPadding(pad, pad, pad, pad)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * dp).toInt()
                }
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val actionTv = TextView(this).apply {
                text = log.action
                setTextColor(ContextCompat.getColor(this@AuditLogActivity, R.color.bank_text_primary))
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val statusTv = TextView(this).apply {
                text = log.status
                textSize = 10f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding((8 * dp).toInt(), (3 * dp).toInt(), (8 * dp).toInt(), (3 * dp).toInt())
                when (log.status.uppercase()) {
                    "APPROVED", "VERIFIED", "PASSED", "COMPLETED", "ACTIVE" -> {
                        setBackgroundResource(R.drawable.bg_badge_success)
                        setTextColor(ContextCompat.getColor(this@AuditLogActivity, R.color.status_success_text))
                    }
                    "REJECTED", "FAILED", "EXPIRED", "FAILED_MAX_ATTEMPTS" -> {
                        setBackgroundResource(R.drawable.bg_badge_error)
                        setTextColor(ContextCompat.getColor(this@AuditLogActivity, R.color.status_error_text))
                    }
                    "PENDING" -> {
                        setBackgroundResource(R.drawable.bg_badge_warning)
                        setTextColor(ContextCompat.getColor(this@AuditLogActivity, R.color.status_warning_text))
                    }
                    else -> {
                        setBackgroundResource(R.drawable.bg_badge_info)
                        setTextColor(ContextCompat.getColor(this@AuditLogActivity, R.color.status_info_text))
                    }
                }
            }
            topRow.addView(actionTv)
            topRow.addView(statusTv)
            card.addView(topRow)

            val metaTv = TextView(this).apply {
                text = "Actor: ${log.actorId} | Entity: ${log.entityName} (${log.entityId})\n" +
                    "Chi tiết: ${log.details}\n" +
                    "Thời gian: ${FormatAndSecurityUtils.formatTimestamp(log.timestamp)} | ${log.logId}"
                setTextColor(ContextCompat.getColor(this@AuditLogActivity, R.color.bank_text_secondary))
                textSize = 12f
                setPadding(0, (6 * dp).toInt(), 0, 0)
            }
            card.addView(metaTv)

            containerAuditLogs.addView(card)
        }
    }
}

