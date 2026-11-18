package com.example.bankingapp.common.model

import java.math.BigDecimal

data class BankAccount(
    val accountId: String,
    val accountNumber: String,
    val userId: String,
    val accountType: AccountType,
    val balance: BigDecimal,
    val currency: String = "VND",
    val status: AccountStatus = AccountStatus.ACTIVE,
    val ownerName: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun canTransact(): Boolean = status == AccountStatus.ACTIVE

    fun hasSufficientBalance(amt: BigDecimal): Boolean = balance.compareTo(amt) >= 0
}

