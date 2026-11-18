package com.example.bankingapp.features.transactions

import com.example.bankingapp.common.model.AccountStatus
import com.example.bankingapp.common.model.AccountType
import com.example.bankingapp.common.model.BankAccount
import com.example.bankingapp.common.model.User
import com.example.bankingapp.common.model.UserRole
import com.example.bankingapp.common.model.UserStatus
import com.example.bankingapp.common.repository.AccountRepository
import com.example.bankingapp.common.utils.FormatAndSecurityUtils
import java.math.BigDecimal

data class ExternalBankInfo(
    val bankCode: String,
    val bankName: String
)

data class SandboxGatewayOption(
    val gatewayId: String,
    val displayName: String,
    val modeLabel: String,
    val mockMaskedSource: String
)

object ExternalBankDirectory {
    val supportedBanks = listOf(
        ExternalBankInfo("VCB", "Vietcombank (VCB)"),
        ExternalBankInfo("MB", "MB Bank (MB)"),
        ExternalBankInfo("TCB", "Techcombank (TCB)"),
        ExternalBankInfo("BIDV", "BIDV"),
        ExternalBankInfo("ACB", "ACB")
    )

    private val sampleAccounts = mapOf(
        "VCB:0071001234567" to "PHAM MINH TUAN",
        "MB:9704221122334" to "HOANG THI MAI",
        "TCB:1903456789012" to "NGUYEN DUC ANH",
        "BIDV:3141000112233" to "TRAN QUOC BAO",
        "ACB:128900112233" to "VO THI HUONG"
    )

    fun lookupReceiverName(bankCode: String, accountNumber: String): String? {
        return sampleAccounts["${bankCode.uppercase()}:${accountNumber.trim()}"]
    }

    fun getBankName(bankCode: String): String {
        return supportedBanks.firstOrNull { it.bankCode.equals(bankCode, ignoreCase = true) }?.bankName
            ?: bankCode
    }
}

object SandboxGatewayConfig {
    // Only test/sandbox identifiers are defined here — zero API secrets or production keys.
    val gateways = listOf(
        SandboxGatewayOption(
            gatewayId = "VNPAY_SANDBOX",
            displayName = "VNPay Sandbox Test Gateway",
            modeLabel = "TEST MODE (No real charge)",
            mockMaskedSource = "Thẻ test VNPay 9704****0001"
        ),
        SandboxGatewayOption(
            gatewayId = "MOMO_SANDBOX",
            displayName = "MoMo Developer Sandbox",
            modeLabel = "SANDBOX MODE (Simulated)",
            mockMaskedSource = "Ví MoMo Sandbox ***889"
        ),
        SandboxGatewayOption(
            gatewayId = "NAPAS_SANDBOX",
            displayName = "Cổng Napas 24/7 Sandbox",
            modeLabel = "TEST MODE (Mock Bank Link)",
            mockMaskedSource = "Thẻ Napas Test 9704****1234"
        )
    )
}

/**
 * Standalone mock AccountRepository for Member 2 so Security & Transaction flows
 * run and test independently without depending on Member 1's Core Banking implementation.
 */
class MockAccountRepository : AccountRepository {

    private val accounts = LinkedHashMap<String, BankAccount>()
    private val users = LinkedHashMap<String, User>()

    init {
        resetDemoAccounts()
    }

    @Synchronized
    fun resetDemoAccounts() {
        accounts.clear()
        users.clear()

        val now = System.currentTimeMillis()
        users["user_cust_01"] = User(
            userId = "user_cust_01",
            username = "nguyenvanan",
            passwordHash = FormatAndSecurityUtils.sha256("demo_only_not_used"),
            fullName = "Nguyễn Văn An",
            email = "an.nguyen@example.com",
            phoneNumber = "0901234567",
            role = UserRole.CUSTOMER,
            status = UserStatus.ACTIVE,
            createdAt = now
        )
        users["user_cust_02"] = User(
            userId = "user_cust_02",
            username = "tranthibinh",
            passwordHash = FormatAndSecurityUtils.sha256("demo_only_not_used"),
            fullName = "Trần Thị Bình",
            email = "binh.tran@example.com",
            phoneNumber = "0912345678",
            role = UserRole.CUSTOMER,
            status = UserStatus.ACTIVE,
            createdAt = now
        )
        users["user_officer_01"] = User(
            userId = "user_officer_01",
            username = "officer_minh",
            passwordHash = FormatAndSecurityUtils.sha256("demo_only_not_used"),
            fullName = "Lê Minh (Bank Officer)",
            email = "minh.officer@bankdemo.vn",
            phoneNumber = "0988888888",
            role = UserRole.BANK_OFFICER,
            status = UserStatus.ACTIVE,
            createdAt = now
        )

        seedAccount(
            BankAccount(
                accountId = "acc_checking_01",
                accountNumber = "1001000123456",
                userId = "user_cust_01",
                accountType = AccountType.CHECKING,
                balance = BigDecimal("50000000"),
                status = AccountStatus.ACTIVE,
                ownerName = "Nguyễn Văn An",
                createdAt = now
            )
        )
        seedAccount(
            BankAccount(
                accountId = "acc_saving_01",
                accountNumber = "1002000654321",
                userId = "user_cust_01",
                accountType = AccountType.SAVING,
                balance = BigDecimal("120000000"),
                status = AccountStatus.ACTIVE,
                ownerName = "Nguyễn Văn An",
                createdAt = now
            )
        )
        seedAccount(
            BankAccount(
                accountId = "acc_checking_02",
                accountNumber = "1001000999888",
                userId = "user_cust_02",
                accountType = AccountType.CHECKING,
                balance = BigDecimal("15000000"),
                status = AccountStatus.ACTIVE,
                ownerName = "Trần Thị Bình",
                createdAt = now
            )
        )
        seedAccount(
            BankAccount(
                accountId = "acc_frozen_03",
                accountNumber = "1001000444555",
                userId = "user_cust_03",
                accountType = AccountType.CHECKING,
                balance = BigDecimal("5000000"),
                status = AccountStatus.FROZEN,
                ownerName = "Lê Hoàng Cường (Tài khoản bị khóa)",
                createdAt = now
            )
        )
    }

    @Synchronized
    fun seedAccount(account: BankAccount) {
        accounts[account.accountId] = account
    }

    @Synchronized
    fun getAllAccounts(): List<BankAccount> = accounts.values.toList()

    @Synchronized
    fun getUserById(userId: String): User? = users[userId]

    @Synchronized
    override fun getAccountById(accountId: String): BankAccount? = accounts[accountId]

    @Synchronized
    override fun getAccountByNumber(accNum: String): BankAccount? {
        val normalized = accNum.trim()
        return accounts.values.firstOrNull { it.accountNumber == normalized }
    }

    @Synchronized
    override fun getAccountsByUserId(userId: String): List<BankAccount> {
        return accounts.values.filter { it.userId == userId }
    }

    @Synchronized
    override fun updateBalanceAtomic(srcId: String?, dstId: String?, amt: BigDecimal): Boolean {
        if (amt.compareTo(BigDecimal.ZERO) <= 0) return false
        if (srcId != null && srcId == dstId) return false

        val srcAcc = if (srcId != null) accounts[srcId] ?: return false else null
        val dstAcc = if (dstId != null) accounts[dstId] ?: return false else null

        if (srcAcc != null) {
            if (!srcAcc.canTransact() || !srcAcc.hasSufficientBalance(amt)) {
                return false
            }
        }
        if (dstAcc != null && !dstAcc.canTransact()) {
            return false
        }

        if (srcAcc != null) {
            accounts[srcAcc.accountId] = srcAcc.copy(balance = srcAcc.balance.subtract(amt))
        }
        if (dstAcc != null) {
            accounts[dstAcc.accountId] = dstAcc.copy(balance = dstAcc.balance.add(amt))
        }
        return true
    }
}

