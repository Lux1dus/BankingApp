package com.example.bankingapp.features.transactions

import android.content.Intent
import android.os.Bundle
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
import java.math.BigDecimal

class DepositWithdrawActivity : AppCompatActivity() {

    private lateinit var btnModeDeposit: MaterialButton
    private lateinit var btnModeWithdraw: MaterialButton
    private lateinit var tvAccountLabel: TextView
    private lateinit var spinnerAccounts: Spinner
    private lateinit var cardSandboxGateway: LinearLayout
    private lateinit var spinnerSandboxGateway: Spinner
    private lateinit var etAmount: EditText
    private lateinit var etDescription: EditText
    private lateinit var tvValidationError: TextView

    private var isDepositMode = true
    private var displayedAccounts: List<BankAccount> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_deposit_withdraw)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        btnModeDeposit = findViewById(R.id.btnModeDeposit)
        btnModeWithdraw = findViewById(R.id.btnModeWithdraw)
        tvAccountLabel = findViewById(R.id.tvAccountLabel)
        spinnerAccounts = findViewById(R.id.spinnerAccounts)
        cardSandboxGateway = findViewById(R.id.cardSandboxGateway)
        spinnerSandboxGateway = findViewById(R.id.spinnerSandboxGateway)
        etAmount = findViewById(R.id.etAmount)
        etDescription = findViewById(R.id.etDescription)
        tvValidationError = findViewById(R.id.tvValidationError)

        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { finish() }

        setupSandboxSpinner()
        refreshAccountSpinner()

        btnModeDeposit.setOnClickListener { switchMode(deposit = true) }
        btnModeWithdraw.setOnClickListener { switchMode(deposit = false) }

        findViewById<MaterialButton>(R.id.btnQuickNormal).setOnClickListener {
            etAmount.setText("500000")
            tvValidationError.visibility = View.GONE
        }
        findViewById<MaterialButton>(R.id.btnQuickHigh).setOnClickListener {
            etAmount.setText("15000000")
            tvValidationError.visibility = View.GONE
        }
        findViewById<MaterialButton>(R.id.btnQuickExceed).setOnClickListener {
            etAmount.setText("200000000")
            tvValidationError.visibility = View.GONE
        }
        findViewById<MaterialButton>(R.id.btnQuickZero).setOnClickListener {
            etAmount.setText("0")
            tvValidationError.visibility = View.GONE
        }

        findViewById<MaterialButton>(R.id.btnContinue).setOnClickListener {
            handleSubmit()
        }

        switchMode(deposit = true)
    }

    override fun onResume() {
        super.onResume()
        refreshAccountSpinner()
    }

    private fun setupSandboxSpinner() {
        val labels = SandboxGatewayConfig.gateways.map { "${it.displayName} (${it.mockMaskedSource})" }
        spinnerSandboxGateway.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
    }

    private fun refreshAccountSpinner() {
        val selectedPos = spinnerAccounts.selectedItemPosition.coerceAtLeast(0)
        val repo = DemoSessionProvider.accountRepository
        displayedAccounts = listOfNotNull(
            repo.getAccountById("acc_checking_01"),
            repo.getAccountById("acc_saving_01"),
            repo.getAccountById("acc_frozen_03")
        )
        val labels = displayedAccounts.map { acc ->
            val statusSuffix = if (!acc.canTransact()) " [${acc.status.name}]" else ""
            "${acc.accountType.name} (${acc.accountNumber}) — ${FormatAndSecurityUtils.formatVnd(acc.balance)}$statusSuffix"
        }
        spinnerAccounts.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        if (selectedPos < labels.size) {
            spinnerAccounts.setSelection(selectedPos)
        }
    }

    private fun switchMode(deposit: Boolean) {
        isDepositMode = deposit
        tvValidationError.visibility = View.GONE
        if (deposit) {
            btnModeDeposit.setBackgroundColor(ContextCompat.getColor(this, R.color.bank_primary))
            btnModeDeposit.setTextColor(ContextCompat.getColor(this, R.color.white))
            btnModeWithdraw.setBackgroundColor(ContextCompat.getColor(this, R.color.white))
            btnModeWithdraw.setTextColor(ContextCompat.getColor(this, R.color.bank_primary))
            tvAccountLabel.text = "Chọn tài khoản nhận tiền nạp"
            cardSandboxGateway.visibility = View.VISIBLE
            etDescription.setText("Nạp tiền qua cổng Sandbox")
        } else {
            btnModeWithdraw.setBackgroundColor(ContextCompat.getColor(this, R.color.bank_primary))
            btnModeWithdraw.setTextColor(ContextCompat.getColor(this, R.color.white))
            btnModeDeposit.setBackgroundColor(ContextCompat.getColor(this, R.color.white))
            btnModeDeposit.setTextColor(ContextCompat.getColor(this, R.color.bank_primary))
            tvAccountLabel.text = "Chọn tài khoản nguồn rút tiền"
            cardSandboxGateway.visibility = View.GONE
            etDescription.setText("Rút tiền về tài khoản liên kết")
        }
    }

    private fun handleSubmit() {
        tvValidationError.visibility = View.GONE
        val selectedIndex = spinnerAccounts.selectedItemPosition
        if (selectedIndex !in displayedAccounts.indices) {
            showError("Vui lòng chọn tài khoản hợp lệ")
            return
        }
        val selectedAccount = displayedAccounts[selectedIndex]
        val rawAmount = etAmount.text.toString().trim()
        val amount = rawAmount.toBigDecimalOrNull()
        if (amount == null) {
            showError("Vui lòng nhập số tiền hợp lệ")
            return
        }

        val descInput = etDescription.text.toString().trim()
        try {
            val tx = if (isDepositMode) {
                val gwIndex = spinnerSandboxGateway.selectedItemPosition.coerceAtLeast(0)
                val gw = SandboxGatewayConfig.gateways.getOrNull(gwIndex)
                val fullDesc = if (gw != null) "$descInput [${gw.gatewayId}]" else descInput
                DemoSessionProvider.transactionService.processDeposit(
                    accId = selectedAccount.accountId,
                    amount = amount,
                    description = fullDesc
                )
            } else {
                DemoSessionProvider.transactionService.processWithdraw(
                    srcAccId = selectedAccount.accountId,
                    amount = amount,
                    description = descInput
                )
            }

            val intent = Intent(this, TransactionConfirmActivity::class.java).apply {
                putExtra(TransactionConfirmActivity.EXTRA_TX_ID, tx.transactionId)
            }
            startActivity(intent)
        } catch (e: IllegalArgumentException) {
            showError(e.message ?: "Dữ liệu giao dịch không hợp lệ")
        }
    }

    private fun showError(msg: String) {
        tvValidationError.text = "Lỗi kiểm tra (Validation): $msg"
        tvValidationError.visibility = View.VISIBLE
    }
}

