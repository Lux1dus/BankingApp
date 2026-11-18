package com.example.bankingapp.common.model

import java.math.BigDecimal

data class Transaction(
    val transactionId: String,
    val userId: String,
    val transactionType: TransactionType,
    val sourceAccountId: String? = null,
    val targetAccountId: String? = null,
    val targetAccountNumber: String? = null,
    val targetBankCode: String? = null,
    val receiverName: String? = null,
    val amount: BigDecimal,
    val currency: String = "VND",
    val description: String = "",
    val status: TransactionStatus = TransactionStatus.PENDING,
    val isHighValue: Boolean = false,
    val is2faVerified: Boolean = false,
    val isBiometricVerified: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
) {
    fun isHighValue(threshold: BigDecimal): Boolean = amount.compareTo(threshold) >= 0

    fun isTerminalState(): Boolean = status in setOf(
        TransactionStatus.COMPLETED,
        TransactionStatus.FAILED,
        TransactionStatus.CANCELLED,
        TransactionStatus.REFUNDED
    )
}

