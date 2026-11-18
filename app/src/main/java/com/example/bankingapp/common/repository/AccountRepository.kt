package com.example.bankingapp.common.repository

import com.example.bankingapp.common.model.BankAccount
import java.math.BigDecimal

/**
 * Shared contract for account queries and atomic balance updates across modules.
 */
interface AccountRepository {
    fun getAccountById(accountId: String): BankAccount?
    fun getAccountByNumber(accNum: String): BankAccount?
    fun getAccountsByUserId(userId: String): List<BankAccount>
    fun updateBalanceAtomic(srcId: String?, dstId: String?, amt: BigDecimal): Boolean
}

