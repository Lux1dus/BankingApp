package com.example.bankingapp.features.transactions

import com.example.bankingapp.common.model.Transaction
import com.example.bankingapp.common.model.TransactionStatus
import com.example.bankingapp.common.model.TransactionType
import com.example.bankingapp.features.security.EkycStatus
import com.example.bankingapp.features.security.EkycVerification
import com.example.bankingapp.features.security.SecurityService
import java.math.BigDecimal

/**
 * Singleton state holder enabling Member 2's Security & Transaction screens
 * to share consistent in-memory mock balances, eKYC statuses, transactions, and audit logs.
 */
object DemoSessionProvider {

    const val CURRENT_CUSTOMER_ID = "user_cust_01"
    const val CURRENT_OFFICER_ID = "user_officer_01"

    val accountRepository: MockAccountRepository = MockAccountRepository()
    val securityService: SecurityService = SecurityService()
    val transactionService: TransactionService = TransactionService(accountRepository, securityService)

    init {
        seedInitialDemoData()
    }

    @Synchronized
    fun resetDemoData() {
        accountRepository.resetDemoAccounts()
        securityService.clearAll()
        transactionService.clearAll()
        seedInitialDemoData()
    }

    private fun seedInitialDemoData() {
        val now = System.currentTimeMillis()

        // Pre-seed 2 eKYC submissions for Customer & Officer Review demo
        securityService.seedEkyc(
            EkycVerification(
                ekycId = "EKYC-DEMO0001",
                userId = CURRENT_CUSTOMER_ID,
                idCardNumber = "079099001234",
                documentPhotoUrl = "demo://cccd_nguyen_van_an.jpg",
                facePhotoUrl = "demo://selfie_nguyen_van_an.jpg",
                status = EkycStatus.PENDING,
                createdAt = now - 3_600_000L,
                updatedAt = now - 3_600_000L,
                fullName = "Nguyễn Văn An",
                dateOfBirth = "15/08/1999",
                address = "19 Nguyễn Hữu Thọ, Quận 7, TP.HCM"
            )
        )
        securityService.seedEkyc(
            EkycVerification(
                ekycId = "EKYC-DEMO0002",
                userId = "user_cust_02",
                idCardNumber = "079200005678",
                documentPhotoUrl = "demo://cccd_tran_thi_binh.jpg",
                facePhotoUrl = "demo://selfie_tran_thi_binh.jpg",
                status = EkycStatus.PENDING,
                createdAt = now - 7_200_000L,
                updatedAt = now - 7_200_000L,
                fullName = "Trần Thị Bình",
                dateOfBirth = "20/11/2000",
                address = "45 Lê Lợi, Quận 1, TP.HCM"
            )
        )

        // Pre-seed 3 sample historical transactions
        transactionService.saveTransaction(
            Transaction(
                transactionId = "DEP-SEED1001",
                userId = CURRENT_CUSTOMER_ID,
                transactionType = TransactionType.DEPOSIT,
                targetAccountId = "acc_checking_01",
                targetAccountNumber = "1001000123456",
                receiverName = "Nguyễn Văn An",
                amount = BigDecimal("10000000"),
                description = "Nạp tiền khởi tạo tài khoản qua VNPay Sandbox",
                status = TransactionStatus.COMPLETED,
                isHighValue = true,
                is2faVerified = true,
                isBiometricVerified = true,
                createdAt = now - 86_400_000L,
                completedAt = now - 86_390_000L
            )
        )
        transactionService.saveTransaction(
            Transaction(
                transactionId = "TRF-SEED1002",
                userId = CURRENT_CUSTOMER_ID,
                transactionType = TransactionType.INTERNAL_TRANSFER,
                sourceAccountId = "acc_checking_01",
                targetAccountId = "acc_checking_02",
                targetAccountNumber = "1001000999888",
                receiverName = "Trần Thị Bình",
                amount = BigDecimal("2500000"),
                description = "Thanh toán tiền ăn trưa nhóm",
                status = TransactionStatus.COMPLETED,
                isHighValue = false,
                is2faVerified = true,
                isBiometricVerified = false,
                createdAt = now - 43_200_000L,
                completedAt = now - 43_190_000L
            )
        )
        transactionService.saveTransaction(
            Transaction(
                transactionId = "EXT-SEED1003",
                userId = CURRENT_CUSTOMER_ID,
                transactionType = TransactionType.EXTERNAL_TRANSFER,
                sourceAccountId = "acc_checking_01",
                targetAccountNumber = "0071001234567",
                targetBankCode = "VCB",
                receiverName = "PHAM MINH TUAN",
                amount = BigDecimal("1500000"),
                description = "Chuyển tiền liên ngân hàng Vietcombank",
                status = TransactionStatus.COMPLETED,
                isHighValue = false,
                is2faVerified = true,
                isBiometricVerified = false,
                createdAt = now - 14_400_000L,
                completedAt = now - 14_390_000L
            )
        )

        securityService.logEvent(
            actorId = "SYSTEM",
            action = "DEMO_ENVIRONMENT_READY",
            entity = "member2_module",
            entityId = "INIT",
            status = "ACTIVE",
            details = "Khởi tạo dữ liệu giả lập độc lập cho Security & Transaction Management"
        )
    }
}

