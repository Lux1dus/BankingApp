package com.example.bankingapp.common.repository

import com.example.bankingapp.common.model.Transaction
import com.example.bankingapp.common.model.TransactionStatus
import java.math.BigDecimal

/**
 * Shared contract owned by Member 2 (Security & Transaction Management)
 * consumed by Member 1 (history display) and Member 3 (utility payments & refunds).
 */
interface TransactionRepository {
    fun saveTransaction(tx: Transaction): Boolean
    fun getTransactionById(txId: String): Transaction?
    fun getTransactionsByAccount(accId: String): List<Transaction>
    fun updateTransactionStatus(txId: String, status: TransactionStatus): Boolean
    fun createUtilityPayment(userId: String, srcAccId: String, amt: BigDecimal, desc: String): Transaction
    fun refundTransaction(txId: String, reason: String): Boolean
}

