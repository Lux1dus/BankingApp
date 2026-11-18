package com.example.bankingapp.features.security

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.bankingapp.R
import com.example.bankingapp.common.utils.FormatAndSecurityUtils
import com.example.bankingapp.features.transactions.DemoSessionProvider
import com.google.android.material.button.MaterialButton

class EkycActivity : AppCompatActivity() {

    private lateinit var tvCurrentEkycStatus: TextView
    private lateinit var tvEkycDetailNote: TextView
    private lateinit var etFullName: EditText
    private lateinit var etIdCard: EditText
    private lateinit var etDob: EditText
    private lateinit var etAddress: EditText
    private lateinit var ivDocPhoto: ImageView
    private lateinit var tvDocPhotoStatus: TextView
    private lateinit var ivFacePhoto: ImageView
    private lateinit var tvFacePhotoStatus: TextView
    private lateinit var btnCaptureDoc: MaterialButton
    private lateinit var btnPickDoc: MaterialButton
    private lateinit var btnSampleDoc: MaterialButton
    private lateinit var btnCaptureFace: MaterialButton
    private lateinit var btnPickFace: MaterialButton
    private lateinit var btnSampleFace: MaterialButton
    private lateinit var btnSubmitEkyc: MaterialButton

    private var docPhotoUrl: String = ""
    private var facePhotoUrl: String = ""

    private val captureDocLauncher = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap: Bitmap? ->
        if (bitmap != null) {
            ivDocPhoto.setImageBitmap(bitmap)
            docPhotoUrl = "camera://cccd_capture_${System.currentTimeMillis()}.jpg"
            tvDocPhotoStatus.text = "Đã chụp ảnh CCCD ($docPhotoUrl)"
        }
    }

    private val pickDocLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            ivDocPhoto.setImageURI(uri)
            docPhotoUrl = uri.toString()
            tvDocPhotoStatus.text = "Đã chọn ảnh CCCD từ thư viện"
        }
    }

    private val captureFaceLauncher = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap: Bitmap? ->
        if (bitmap != null) {
            ivFacePhoto.setImageBitmap(bitmap)
            facePhotoUrl = "camera://face_capture_${System.currentTimeMillis()}.jpg"
            tvFacePhotoStatus.text = "Đã chụp ảnh khuôn mặt ($facePhotoUrl)"
        }
    }

    private val pickFaceLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            ivFacePhoto.setImageURI(uri)
            facePhotoUrl = uri.toString()
            tvFacePhotoStatus.text = "Đã chọn ảnh khuôn mặt từ thư viện"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_ekyc)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        tvCurrentEkycStatus = findViewById(R.id.tvCurrentEkycStatus)
        tvEkycDetailNote = findViewById(R.id.tvEkycDetailNote)
        etFullName = findViewById(R.id.etFullName)
        etIdCard = findViewById(R.id.etIdCard)
        etDob = findViewById(R.id.etDob)
        etAddress = findViewById(R.id.etAddress)
        ivDocPhoto = findViewById(R.id.ivDocPhoto)
        tvDocPhotoStatus = findViewById(R.id.tvDocPhotoStatus)
        ivFacePhoto = findViewById(R.id.ivFacePhoto)
        tvFacePhotoStatus = findViewById(R.id.tvFacePhotoStatus)
        btnCaptureDoc = findViewById(R.id.btnCaptureDoc)
        btnPickDoc = findViewById(R.id.btnPickDoc)
        btnSampleDoc = findViewById(R.id.btnSampleDoc)
        btnCaptureFace = findViewById(R.id.btnCaptureFace)
        btnPickFace = findViewById(R.id.btnPickFace)
        btnSampleFace = findViewById(R.id.btnSampleFace)
        btnSubmitEkyc = findViewById(R.id.btnSubmitEkyc)

        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { finish() }

        // Pre-fill demo customer defaults for rapid testing
        val latest = DemoSessionProvider.securityService.getLatestEkycForUser(DemoSessionProvider.CURRENT_CUSTOMER_ID)
        etFullName.setText(latest?.fullName?.ifBlank { "Nguyễn Văn An" } ?: "Nguyễn Văn An")
        etIdCard.setText(latest?.idCardNumber?.ifBlank { "079099001234" } ?: "079099001234")
        etDob.setText(latest?.dateOfBirth?.ifBlank { "15/08/1999" } ?: "15/08/1999")
        etAddress.setText(latest?.address?.ifBlank { "19 Nguyễn Hữu Thọ, Quận 7, TP.HCM" } ?: "19 Nguyễn Hữu Thọ, Quận 7, TP.HCM")

        btnCaptureDoc.setOnClickListener {
            captureDocLauncher.launch(null)
        }
        btnPickDoc.setOnClickListener {
            pickDocLauncher.launch("image/*")
        }
        btnSampleDoc.setOnClickListener {
            docPhotoUrl = "demo://cccd_sample_front.jpg"
            ivDocPhoto.setImageResource(android.R.drawable.ic_menu_report_image)
            tvDocPhotoStatus.text = "Đã gắn ảnh mẫu CCCD ($docPhotoUrl)"
        }

        btnCaptureFace.setOnClickListener {
            captureFaceLauncher.launch(null)
        }
        btnPickFace.setOnClickListener {
            pickFaceLauncher.launch("image/*")
        }
        btnSampleFace.setOnClickListener {
            facePhotoUrl = "demo://selfie_sample_face.jpg"
            ivFacePhoto.setImageResource(android.R.drawable.ic_menu_myplaces)
            tvFacePhotoStatus.text = "Đã gắn ảnh mẫu khuôn mặt ($facePhotoUrl)"
        }

        btnSubmitEkyc.setOnClickListener {
            submitEkycForm()
        }

        refreshEkycStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshEkycStatus()
    }

    private fun submitEkycForm() {
        val fullName = etFullName.text.toString().trim()
        val idCard = etIdCard.text.toString().trim()
        val dob = etDob.text.toString().trim()
        val address = etAddress.text.toString().trim()

        if (fullName.isBlank()) {
            etFullName.error = "Vui lòng nhập họ và tên"
            return
        }
        if (!idCard.matches(Regex("^\\d{9}|\\d{12}$"))) {
            etIdCard.error = "CCCD/CMND phải gồm 9 hoặc 12 chữ số"
            return
        }
        if (docPhotoUrl.isBlank()) {
            Toast.makeText(this, "Vui lòng chụp, chọn hoặc gắn ảnh mẫu CCCD", Toast.LENGTH_SHORT).show()
            return
        }
        if (facePhotoUrl.isBlank()) {
            Toast.makeText(this, "Vui lòng chụp, chọn hoặc gắn ảnh mẫu khuôn mặt", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val record = DemoSessionProvider.securityService.submitEkyc(
                userId = DemoSessionProvider.CURRENT_CUSTOMER_ID,
                idCard = idCard,
                docUrl = docPhotoUrl,
                faceUrl = facePhotoUrl,
                fullName = fullName,
                dob = dob,
                address = address
            )

            Toast.makeText(
                this,
                "Đã gửi hồ sơ ${record.ekycId} (Trạng thái: PENDING). Vào mục Bank Officer để duyệt!",
                Toast.LENGTH_LONG
            ).show()
            refreshEkycStatus()
        } catch (e: IllegalStateException) {
            Toast.makeText(this, e.message ?: "Không thể gửi yêu cầu eKYC lúc này", Toast.LENGTH_LONG).show()
        }
    }

    private fun setFormEnabled(enabled: Boolean) {
        etFullName.isEnabled = enabled
        etIdCard.isEnabled = enabled
        etDob.isEnabled = enabled
        etAddress.isEnabled = enabled
        btnCaptureDoc.isEnabled = enabled
        btnPickDoc.isEnabled = enabled
        btnSampleDoc.isEnabled = enabled
        btnCaptureFace.isEnabled = enabled
        btnPickFace.isEnabled = enabled
        btnSampleFace.isEnabled = enabled
        btnSubmitEkyc.isEnabled = enabled
        btnSubmitEkyc.alpha = if (enabled) 1.0f else 0.5f
    }

    private fun refreshEkycStatus() {
        val security = DemoSessionProvider.securityService
        val latest = security.getLatestEkycForUser(DemoSessionProvider.CURRENT_CUSTOMER_ID)
        val attempts = security.getEkycAttemptCount(DemoSessionProvider.CURRENT_CUSTOMER_ID)
        if (latest == null) {
            tvCurrentEkycStatus.text = "CHƯA NỘP HỒ SƠ"
            tvCurrentEkycStatus.setBackgroundResource(R.drawable.bg_badge_info)
            tvCurrentEkycStatus.setTextColor(ContextCompat.getColor(this, R.color.status_info_text))
            tvEkycDetailNote.text = "Hãy điền thông tin và chọn ảnh định danh bên dưới."
            setFormEnabled(true)
            btnSubmitEkyc.text = "Gửi hồ sơ eKYC (Trạng thái: Pending)"
            return
        }

        if (docPhotoUrl.isBlank() && latest.documentPhotoUrl.isNotBlank()) {
            docPhotoUrl = latest.documentPhotoUrl
            tvDocPhotoStatus.text = "Ảnh hiện tại: ${latest.documentPhotoUrl}"
        }
        if (facePhotoUrl.isBlank() && latest.facePhotoUrl.isNotBlank()) {
            facePhotoUrl = latest.facePhotoUrl
            tvFacePhotoStatus.text = "Ảnh hiện tại: ${latest.facePhotoUrl}"
        }

        tvCurrentEkycStatus.text = latest.status.name
        when (latest.status) {
            EkycStatus.PENDING -> {
                tvCurrentEkycStatus.setBackgroundResource(R.drawable.bg_badge_warning)
                tvCurrentEkycStatus.setTextColor(ContextCompat.getColor(this, R.color.status_warning_text))
                tvEkycDetailNote.text = "Mã hồ sơ: ${latest.ekycId} (Lần gửi #$attempts) • Đang chờ duyệt (${FormatAndSecurityUtils.formatTimestamp(latest.updatedAt)}). Bạn chỉ có thể gửi lại nếu hồ sơ bị từ chối."
                setFormEnabled(false)
                btnSubmitEkyc.text = "Đang chờ Bank Officer duyệt (Không thể tạo thêm)"
            }
            EkycStatus.APPROVED -> {
                tvCurrentEkycStatus.setBackgroundResource(R.drawable.bg_badge_success)
                tvCurrentEkycStatus.setTextColor(ContextCompat.getColor(this, R.color.status_success_text))
                tvEkycDetailNote.text = "Mã hồ sơ: ${latest.ekycId} • Đã được duyệt bởi ${latest.reviewedBy ?: "Officer"}. Không cần tạo thêm yêu cầu."
                setFormEnabled(false)
                btnSubmitEkyc.text = "Đã định danh eKYC thành công"
            }
            EkycStatus.REJECTED -> {
                tvCurrentEkycStatus.setBackgroundResource(R.drawable.bg_badge_error)
                tvCurrentEkycStatus.setTextColor(ContextCompat.getColor(this, R.color.status_error_text))
                tvEkycDetailNote.text = "Mã hồ sơ: ${latest.ekycId} • Bị từ chối bởi ${latest.reviewedBy ?: "Officer"}. Lý do: ${latest.rejectionReason}. Vui lòng cập nhật và gửi lại!"
                setFormEnabled(true)
                btnSubmitEkyc.text = "Gửi lại hồ sơ eKYC (Lần #${attempts + 1})"
            }
        }
    }
}

