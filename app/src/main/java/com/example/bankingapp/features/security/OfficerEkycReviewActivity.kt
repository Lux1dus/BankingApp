package com.example.bankingapp.features.security

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
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
import com.example.bankingapp.common.utils.FormatAndSecurityUtils
import com.example.bankingapp.common.utils.UiFilterHelper
import com.example.bankingapp.features.transactions.DemoSessionProvider
import com.google.android.material.button.MaterialButton

class OfficerEkycReviewActivity : AppCompatActivity() {

    private lateinit var containerEkycList: LinearLayout
    private var currentFilter: EkycStatus? = null
    private lateinit var filterButtons: List<MaterialButton>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_officer_ekyc_review)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        containerEkycList = findViewById(R.id.containerEkycList)

        val btnFilterAll = findViewById<MaterialButton>(R.id.btnFilterAll)
        val btnFilterPending = findViewById<MaterialButton>(R.id.btnFilterPending)
        val btnFilterApproved = findViewById<MaterialButton>(R.id.btnFilterApproved)
        val btnFilterRejected = findViewById<MaterialButton>(R.id.btnFilterRejected)
        filterButtons = listOf(btnFilterAll, btnFilterPending, btnFilterApproved, btnFilterRejected)

        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { finish() }
        btnFilterAll.setOnClickListener {
            currentFilter = null
            UiFilterHelper.highlightSelectedFilter(this, btnFilterAll, filterButtons, R.color.bank_accent)
            renderList()
        }
        btnFilterPending.setOnClickListener {
            currentFilter = EkycStatus.PENDING
            UiFilterHelper.highlightSelectedFilter(this, btnFilterPending, filterButtons, R.color.bank_accent)
            renderList()
        }
        btnFilterApproved.setOnClickListener {
            currentFilter = EkycStatus.APPROVED
            UiFilterHelper.highlightSelectedFilter(this, btnFilterApproved, filterButtons, R.color.bank_accent)
            renderList()
        }
        btnFilterRejected.setOnClickListener {
            currentFilter = EkycStatus.REJECTED
            UiFilterHelper.highlightSelectedFilter(this, btnFilterRejected, filterButtons, R.color.bank_accent)
            renderList()
        }

        UiFilterHelper.highlightSelectedFilter(this, btnFilterAll, filterButtons, R.color.bank_accent)
        renderList()
    }

    override fun onResume() {
        super.onResume()
        renderList()
    }

    private fun renderList() {
        containerEkycList.removeAllViews()
        val all = DemoSessionProvider.securityService.getAllEkycList()
        val filtered = if (currentFilter == null) all else all.filter { it.status == currentFilter }

        if (filtered.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "Không có hồ sơ eKYC nào trong mục này."
                setTextColor(ContextCompat.getColor(this@OfficerEkycReviewActivity, R.color.bank_text_secondary))
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(16, 48, 16, 48)
            }
            containerEkycList.addView(emptyTv)
            return
        }

        val dp = resources.displayMetrics.density
        filtered.forEach { item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_card_rounded)
                val pad = (14 * dp).toInt()
                setPadding(pad, pad, pad, pad)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (12 * dp).toInt()
                }
            }

            // Header row with ID + Status Badge
            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val titleTv = TextView(this).apply {
                text = "${item.fullName.ifBlank { item.userId }} (${item.ekycId})"
                setTextColor(ContextCompat.getColor(this@OfficerEkycReviewActivity, R.color.bank_text_primary))
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val badgeTv = TextView(this).apply {
                text = item.status.name
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
                when (item.status) {
                    EkycStatus.APPROVED -> {
                        setBackgroundResource(R.drawable.bg_badge_success)
                        setTextColor(ContextCompat.getColor(this@OfficerEkycReviewActivity, R.color.status_success_text))
                    }
                    EkycStatus.REJECTED -> {
                        setBackgroundResource(R.drawable.bg_badge_error)
                        setTextColor(ContextCompat.getColor(this@OfficerEkycReviewActivity, R.color.status_error_text))
                    }
                    EkycStatus.PENDING -> {
                        setBackgroundResource(R.drawable.bg_badge_warning)
                        setTextColor(ContextCompat.getColor(this@OfficerEkycReviewActivity, R.color.status_warning_text))
                    }
                }
            }
            headerRow.addView(titleTv)
            headerRow.addView(badgeTv)
            card.addView(headerRow)

            // Info lines
            val attempts = DemoSessionProvider.securityService.getEkycAttemptCount(item.userId)
            val detailBuilder = StringBuilder().apply {
                append("User ID: ${item.userId} | CCCD: ${item.idCardNumber} | Lần gửi: #$attempts\n")
                if (item.dateOfBirth.isNotBlank()) append("Ngày sinh: ${item.dateOfBirth}\n")
                if (item.address.isNotBlank()) append("Địa chỉ: ${item.address}\n")
                append("Ảnh CCCD: ${item.documentPhotoUrl}\n")
                append("Ảnh khuôn mặt: ${item.facePhotoUrl}\n")
                append("Cập nhật: ${FormatAndSecurityUtils.formatTimestamp(item.updatedAt)}")
                if (item.reviewedBy != null) {
                    append("\nNgười duyệt: ${item.reviewedBy}")
                }
                if (!item.rejectionReason.isNullOrBlank()) {
                    append("\nLý do từ chối: ${item.rejectionReason}")
                }
            }
            val infoTv = TextView(this).apply {
                text = detailBuilder.toString()
                setTextColor(ContextCompat.getColor(this@OfficerEkycReviewActivity, R.color.bank_text_secondary))
                textSize = 12f
                setPadding(0, (8 * dp).toInt(), 0, (10 * dp).toInt())
            }
            card.addView(infoTv)

            if (item.status == EkycStatus.PENDING) {
                // Action Buttons Row (Only available while PENDING)
                val btnRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                val btnApprove = MaterialButton(this).apply {
                    text = "Duyệt (Approve)"
                    textSize = 12f
                    setBackgroundColor(ContextCompat.getColor(this@OfficerEkycReviewActivity, R.color.status_success_text))
                    setTextColor(ContextCompat.getColor(this@OfficerEkycReviewActivity, R.color.white))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = (6 * dp).toInt()
                    }
                    setOnClickListener {
                        DemoSessionProvider.securityService.reviewEkyc(
                            ekycId = item.ekycId,
                            officerId = DemoSessionProvider.CURRENT_OFFICER_ID,
                            approved = true,
                            reason = null
                        )
                        Toast.makeText(this@OfficerEkycReviewActivity, "Đã duyệt hồ sơ ${item.ekycId}", Toast.LENGTH_SHORT).show()
                        renderList()
                    }
                }
                val btnReject = MaterialButton(this).apply {
                    text = "Từ chối (Reject)"
                    textSize = 12f
                    setBackgroundColor(ContextCompat.getColor(this@OfficerEkycReviewActivity, R.color.status_error_text))
                    setTextColor(ContextCompat.getColor(this@OfficerEkycReviewActivity, R.color.white))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = (6 * dp).toInt()
                    }
                    setOnClickListener {
                        showRejectReasonDialog(item)
                    }
                }
                btnRow.addView(btnApprove)
                btnRow.addView(btnReject)
                card.addView(btnRow)
            } else {
                val lockedTv = TextView(this).apply {
                    text = if (item.status == EkycStatus.APPROVED) {
                        "✓ Đã phê duyệt định danh cho ${item.userId} — Quyết định đã được chốt"
                    } else {
                        "✕ Đã từ chối — Chờ khách hàng (${item.userId}) cập nhật và gửi lại hồ sơ mới để duyệt lại"
                    }
                    textSize = 12f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding((10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt())
                    if (item.status == EkycStatus.APPROVED) {
                        setBackgroundResource(R.drawable.bg_badge_success)
                        setTextColor(ContextCompat.getColor(this@OfficerEkycReviewActivity, R.color.status_success_text))
                    } else {
                        setBackgroundResource(R.drawable.bg_badge_error)
                        setTextColor(ContextCompat.getColor(this@OfficerEkycReviewActivity, R.color.status_error_text))
                    }
                }
                card.addView(lockedTv)
            }

            containerEkycList.addView(card)
        }
    }

    private fun showRejectReasonDialog(item: EkycVerification) {
        val input = EditText(this).apply {
            hint = "Nhập lý do từ chối hồ sơ"
            setText("Ảnh CCCD bị mờ, vui lòng chụp lại rõ nét")
            setPadding(32, 24, 32, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("Từ chối hồ sơ ${item.ekycId}")
            .setMessage("Nhập lý do từ chối gửi tới khách hàng ${item.fullName}:")
            .setView(input)
            .setPositiveButton("Xác nhận Từ chối") { _, _ ->
                val reason = input.text.toString().trim()
                DemoSessionProvider.securityService.reviewEkyc(
                    ekycId = item.ekycId,
                    officerId = DemoSessionProvider.CURRENT_OFFICER_ID,
                    approved = false,
                    reason = reason
                )
                Toast.makeText(this, "Đã từ chối hồ sơ ${item.ekycId}", Toast.LENGTH_SHORT).show()
                renderList()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }
}

