package com.example.bankingapp.features.transactions

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.bankingapp.R
import com.example.bankingapp.common.model.BankAccount
import com.example.bankingapp.common.utils.FormatAndSecurityUtils
import com.google.android.material.button.MaterialButton

class TransferActivity : AppCompatActivity() {

    private lateinit var btnModeInternal: MaterialButton
    private lateinit var btnModeExternal: MaterialButton
    private lateinit var spinnerSourceAccounts: Spinner
    private lateinit var sectionInternal: LinearLayout
    private lateinit var sectionExternal: LinearLayout
    private lateinit var etInternalTargetAcc: EditText
    private lateinit var tvInternalReceiverLookup: TextView
    private lateinit var spinnerExternalBanks: Spinner
    private lateinit var etExternalTargetAcc: EditText
    private lateinit var etExternalReceiverName: EditText
    private lateinit var etAmount: EditText
    private lateinit var etDescription: EditText
    private lateinit var tvValidationError: TextView

    private var isExternalMode = false
    private var displayedSourceAccounts: List<BankAccount> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_transfer)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        btnModeInternal = findViewById(R.id.btnModeInternal)
        btnModeExternal = findViewById(R.id.btnModeExternal)
        spinnerSourceAccounts = findViewById(R.id.spinnerSourceAccounts)
        sectionInternal = findViewById(R.id.sectionInternal)
        sectionExternal = findViewById(R.id.sectionExternal)
        etInternalTargetAcc = findViewById(R.id.etInternalTargetAcc)
        tvInternalReceiverLookup = findViewById(R.id.tvInternalReceiverLookup)
        spinnerExternalBanks = findViewById(R.id.spinnerExternalBanks)
        etExternalTargetAcc = findViewById(R.id.etExternalTargetAcc)
        etExternalReceiverName = findViewById(R.id.etExternalReceiverName)
        etAmount = findViewById(R.id.etAmount)
        etDescription = findViewById(R.id.etDescription)
        tvValidationError = findViewById(R.id.tvValidationError)

        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { finish() }

        setupExternalBankSpinner()
        refreshSourceAccounts()

        btnModeInternal.setOnClickListener { switchMode(external = false) }
        btnModeExternal.setOnClickListener { switchMode(external = true) }

        etInternalTargetAcc.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateInternalReceiverLookup()
            }
        })

        findViewById<MaterialButton>(R.id.btnSampleInternalValid).setOnClickListener {
            etInternalTargetAcc.setText("1001000999888")
            tvValidationError.visibility = View.GONE
        }
        findViewById<MaterialButton>(R.id.btnSampleInternalFrozen).setOnClickListener {
            etInternalTargetAcc.setText("1001000444555")
            tvValidationError.visibility = View.GONE
        }
        findViewById<MaterialButton>(R.id.btnSampleInternalInvalid).setOnClickListener {
            etInternalTargetAcc.setText("999999999999")
            tvValidationError.visibility = View.GONE
        }

        findViewById<MaterialButton>(R.id.btnSampleVcb).setOnClickListener {
            spinnerExternalBanks.setSelection(0)
            etExternalTargetAcc.setText("0071001234567")
            etExternalReceiverName.setText("PHAM MINH TUAN")
            tvValidationError.visibility = View.GONE
        }
        findViewById<MaterialButton>(R.id.btnSampleMb).setOnClickListener {
            spinnerExternalBanks.setSelection(1)
            etExternalTargetAcc.setText("9704221122334")
            etExternalReceiverName.setText("HOANG THI MAI")
            tvValidationError.visibility = View.GONE
        }

        findViewById<MaterialButton>(R.id.btnAmountNormal).setOnClickListener {
            etAmount.setText("1000000")
            tvValidationError.visibility = View.GONE
        }
        findViewById<MaterialButton>(R.id.btnAmountHigh).setOnClickListener {
            etAmount.setText("15000000")
            tvValidationError.visibility = View.GONE
        }
        findViewById<MaterialButton>(R.id.btnAmountExceed).setOnClickListener {
            etAmount.setText("80000000")
            tvValidationError.visibility = View.GONE
        }
        findViewById<MaterialButton>(R.id.btnAmountNegative).setOnClickListener {
            etAmount.setText("-50000")
            tvValidationError.visibility = View.GONE
        }

        findViewById<MaterialButton>(R.id.btnContinue).setOnClickListener {
            handleTransferSubmit()
        }

        switchMode(external = false)
        updateInternalReceiverLookup()
    }

    override fun onResume() {
        super.onResume()
        refreshSourceAccounts()
    }

    private fun setupExternalBankSpinner() {
        val labels = ExternalBankDirectory.supportedBanks.map { it.bankName }
        spinnerExternalBanks.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
    }

    private fun refreshSourceAccounts() {
        val selectedPos = spinnerSourceAccounts.selectedItemPosition.coerceAtLeast(0)
        val repo = DemoSessionProvider.accountRepository
        displayedSourceAccounts = listOfNotNull(
            repo.getAccountById("acc_checking_01"),
            repo.getAccountById("acc_saving_01"),
            repo.getAccountById("acc_frozen_03")
        )
        val labels = displayedSourceAccounts.map { acc ->
            val statusSuffix = if (!acc.canTransact()) " [${acc.status.name}]" else ""
            "${acc.accountType.name} (${acc.accountNumber}) — ${FormatAndSecurityUtils.formatVnd(acc.balance)}$statusSuffix"
        }
        spinnerSourceAccounts.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        if (selectedPos < labels.size) {
            spinnerSourceAccounts.setSelection(selectedPos)
        }
    }

    private fun switchMode(external: Boolean) {
        isExternalMode = external
        tvValidationError.visibility = View.GONE
        if (!external) {
            btnModeInternal.setBackgroundColor(ContextCompat.getColor(this, R.color.bank_primary))
            btnModeInternal.setTextColor(ContextCompat.getColor(this, R.color.white))
            btnModeExternal.setBackgroundColor(ContextCompat.getColor(this, R.color.white))
            btnModeExternal.setTextColor(ContextCompat.getColor(this, R.color.bank_primary))
            sectionInternal.visibility = View.VISIBLE
            sectionExternal.visibility = View.GONE
        } else {
            btnModeExternal.setBackgroundColor(ContextCompat.getColor(this, R.color.bank_primary))
            btnModeExternal.setTextColor(ContextCompat.getColor(this, R.color.white))
            btnModeInternal.setBackgroundColor(ContextCompat.getColor(this, R.color.white))
            btnModeInternal.setTextColor(ContextCompat.getColor(this, R.color.bank_primary))
            sectionInternal.visibility = View.GONE
            sectionExternal.visibility = View.VISIBLE
        }
    }

    private fun updateInternalReceiverLookup() {
        val num = etInternalTargetAcc.text.toString().trim()
        val acc = DemoSessionProvider.accountRepository.getAccountByNumber(num)
        if (acc == null) {
            tvInternalReceiverLookup.text = "Không tìm thấy tài khoản nội bộ: $num"
            tvInternalReceiverLookup.setBackgroundResource(R.drawable.bg_badge_error)
            tvInternalReceiverLookup.setTextColor(ContextCompat.getColor(this, R.color.status_error_text))
        } else if (!acc.canTransact()) {
            tvInternalReceiverLookup.text = "Người nhận: ${acc.ownerName} [${acc.status.name}]"
            tvInternalReceiverLookup.setBackgroundResource(R.drawable.bg_badge_warning)
            tvInternalReceiverLookup.setTextColor(ContextCompat.getColor(this, R.color.status_warning_text))
        } else {
            tvInternalReceiverLookup.text = "Người nhận: ${acc.ownerName} [${acc.accountType.name} • ACTIVE]"
            tvInternalReceiverLookup.setBackgroundResource(R.drawable.bg_badge_info)
            tvInternalReceiverLookup.setTextColor(ContextCompat.getColor(this, R.color.status_info_text))
        }
    }

    private fun handleTransferSubmit() {
        tvValidationError.visibility = View.GONE
        val srcIndex = spinnerSourceAccounts.selectedItemPosition
        if (srcIndex !in displayedSourceAccounts.indices) {
            showError("Vui lòng chọn tài khoản nguồn")
            return
        }
        val srcAccount = displayedSourceAccounts[srcIndex]
        val amount = etAmount.text.toString().trim().toBigDecimalOrNull()
        if (amount == null) {
            showError("Vui lòng nhập số tiền hợp lệ")
            return
        }

        val desc = etDescription.text.toString().trim()
        try {
            val tx = if (!isExternalMode) {
                DemoSessionProvider.transactionService.processTransfer(
                    srcId = srcAccount.accountId,
                    dstNum = etInternalTargetAcc.text.toString().trim(),
                    bankCode = null,
                    amt = amount,
                    description = desc
                )
            } else {
                val bankIdx = spinnerExternalBanks.selectedItemPosition.coerceAtLeast(0)
                val bankCode = ExternalBankDirectory.supportedBanks.getOrNull(bankIdx)?.bankCode ?: "VCB"
                DemoSessionProvider.transactionService.processTransfer(
                    srcId = srcAccount.accountId,
                    dstNum = etExternalTargetAcc.text.toString().trim(),
                    bankCode = bankCode,
                    amt = amount,
                    description = desc,
                    customReceiverName = etExternalReceiverName.text.toString().trim()
                )
            }

            val intent = Intent(this, TransactionConfirmActivity::class.java).apply {
                putExtra(TransactionConfirmActivity.EXTRA_TX_ID, tx.transactionId)
            }
            startActivity(intent)
        } catch (e: IllegalArgumentException) {
            showError(e.message ?: "Thông tin chuyển tiền không hợp lệ")
        }
    }

    private fun showError(msg: String) {
        tvValidationError.text = "Lỗi kiểm tra (Validation): $msg"
        tvValidationError.visibility = View.VISIBLE
    }
}

