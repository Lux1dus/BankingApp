package com.example.bankingapp.features.transactions

import com.example.bankingapp.common.model.Transaction
import com.example.bankingapp.common.model.TransactionStatus
import com.example.bankingapp.common.model.TransactionType
import com.example.bankingapp.common.repository.AccountRepository
import com.example.bankingapp.common.repository.TransactionRepository
import com.example.bankingapp.common.utils.FormatAndSecurityUtils
import com.example.bankingapp.features.security.EkycStatus
import com.example.bankingapp.features.security.OtpStatus
import com.example.bankingapp.features.security.SecurityService
import java.math.BigDecimal
import java.util.UUID

data class TransactionValidationResult(
    val isValid: Boolean,
    val errorMessage: String? = null
)

/**
 * Core transaction engine for Member 2 implementing [TransactionRepository].
 * Enforces validation rules, high-value biometric threshold, OTP 2FA, and atomic balance updates.
 */
class TransactionService(
    private val accountRepository: AccountRepository,
    private val securityService: SecurityService,
    val highValueThreshold: BigDecimal = BigDecimal("10000000"),
    val singleTransactionLimit: BigDecimal = BigDecimal("200000000")
) : TransactionRepository {

    private val transactions = LinkedHashMap<String, Transaction>()
    private val failureReasons = mutableMapOf<String, String>()

    fun validateTransaction(tx: Transaction): Boolean {
        return validateTransactionDetailed(tx).isValid
    }

    fun validateTransactionDetailed(tx: Transaction): TransactionValidationResult {
        if (tx.amount.compareTo(BigDecimal.ZERO) <= 0) {
            return TransactionValidationResult(
                isValid = false,
                errorMessage = "Số tiền giao dịch phải lớn hơn 0 VNĐ"
            )
        }
        if (tx.amount.compareTo(singleTransactionLimit) > 0) {
            return TransactionValidationResult(
                isValid = false,
                errorMessage = "Số tiền vượt hạn mức tối đa ${FormatAndSecurityUtils.formatVnd(singleTransactionLimit)}/lần"
            )
        }

        if (tx.isHighValue(highValueThreshold)) {
            val latestEkyc = securityService.getLatestEkycForUser(tx.userId)
            if (latestEkyc?.status == EkycStatus.REJECTED) {
                return TransactionValidationResult(
                    isValid = false,
                    errorMessage = "Hồ sơ eKYC đã bị từ chối. Vui lòng cập nhật eKYC trước khi giao dịch >= ${FormatAndSecurityUtils.formatVnd(highValueThreshold)}"
                )
            }
        }

        return when (tx.transactionType) {
            TransactionType.DEPOSIT -> {
                val targetId = tx.targetAccountId
                    ?: return TransactionValidationResult(false, "Thiếu tài khoản nhận tiền nạp")
                val targetAcc = accountRepository.getAccountById(targetId)
                    ?: return TransactionValidationResult(false, "Tài khoản nhận tiền không tồn tại")
                if (!targetAcc.canTransact()) {
                    return TransactionValidationResult(false, "Tài khoản ${targetAcc.accountNumber} đang bị khóa/ngưng hoạt động (${targetAcc.status})")
                }
                TransactionValidationResult(true)
            }

            TransactionType.WITHDRAW, TransactionType.UTILITY_PAYMENT -> {
                val srcId = tx.sourceAccountId
                    ?: return TransactionValidationResult(false, "Thiếu tài khoản nguồn")
                val srcAcc = accountRepository.getAccountById(srcId)
                    ?: return TransactionValidationResult(false, "Tài khoản nguồn không tồn tại")
                if (!srcAcc.canTransact()) {
                    return TransactionValidationResult(false, "Tài khoản ${srcAcc.accountNumber} đang bị khóa/ngưng hoạt động (${srcAcc.status})")
                }
                if (!srcAcc.hasSufficientBalance(tx.amount)) {
                    return TransactionValidationResult(
                        false,
                        "Số dư không đủ (Hiện có: ${FormatAndSecurityUtils.formatVnd(srcAcc.balance)})"
                    )
                }
                TransactionValidationResult(true)
            }

            TransactionType.INTERNAL_TRANSFER -> {
                val srcId = tx.sourceAccountId
                    ?: return TransactionValidationResult(false, "Thiếu tài khoản nguồn")
                val srcAcc = accountRepository.getAccountById(srcId)
                    ?: return TransactionValidationResult(false, "Tài khoản nguồn không tồn tại")
                if (!srcAcc.canTransact()) {
                    return TransactionValidationResult(false, "Tài khoản nguồn ${srcAcc.accountNumber} đang bị khóa/ngưng hoạt động")
                }
                if (!srcAcc.hasSufficientBalance(tx.amount)) {
                    return TransactionValidationResult(
                        false,
                        "Số dư tài khoản nguồn không đủ (Hiện có: ${FormatAndSecurityUtils.formatVnd(srcAcc.balance)})"
                    )
                }

                val targetAcc = tx.targetAccountId?.let { accountRepository.getAccountById(it) }
                    ?: tx.targetAccountNumber?.let { accountRepository.getAccountByNumber(it) }
                    ?: return TransactionValidationResult(false, "Số tài khoản người nhận nội bộ không tồn tại")

                if (targetAcc.accountId == srcAcc.accountId) {
                    return TransactionValidationResult(false, "Không thể chuyển tiền tới chính tài khoản nguồn")
                }
                if (!targetAcc.canTransact()) {
                    return TransactionValidationResult(false, "Tài khoản nhận ${targetAcc.accountNumber} đang bị khóa (${targetAcc.status})")
                }
                TransactionValidationResult(true)
            }

            TransactionType.EXTERNAL_TRANSFER -> {
                val srcId = tx.sourceAccountId
                    ?: return TransactionValidationResult(false, "Thiếu tài khoản nguồn")
                val srcAcc = accountRepository.getAccountById(srcId)
                    ?: return TransactionValidationResult(false, "Tài khoản nguồn không tồn tại")
                if (!srcAcc.canTransact()) {
                    return TransactionValidationResult(false, "Tài khoản nguồn ${srcAcc.accountNumber} đang bị khóa/ngưng hoạt động")
                }
                if (!srcAcc.hasSufficientBalance(tx.amount)) {
                    return TransactionValidationResult(
                        false,
                        "Số dư tài khoản nguồn không đủ (Hiện có: ${FormatAndSecurityUtils.formatVnd(srcAcc.balance)})"
                    )
                }
                if (tx.targetBankCode.isNullOrBlank()) {
                    return TransactionValidationResult(false, "Vui lòng chọn ngân hàng thụ hưởng")
                }
                val dstNum = tx.targetAccountNumber?.trim().orEmpty()
                if (!dstNum.matches(Regex("^\\d{6,18}$"))) {
                    return TransactionValidationResult(false, "Số tài khoản ngân hàng ngoài phải gồm 6–18 chữ số")
                }
                TransactionValidationResult(true)
            }

            TransactionType.REFUND -> TransactionValidationResult(true)
        }
    }

    @Synchronized
    fun processDeposit(
        accId: String,
        amount: BigDecimal,
        description: String = "Nạp tiền qua cổng Sandbox"
    ): Transaction {
        val targetAcc = accountRepository.getAccountById(accId)
        val highValue = amount.compareTo(highValueThreshold) >= 0
        val tx = Transaction(
            transactionId = generateTxId("DEP"),
            userId = targetAcc?.userId ?: "unknown",
            transactionType = TransactionType.DEPOSIT,
            sourceAccountId = null,
            targetAccountId = accId,
            targetAccountNumber = targetAcc?.accountNumber,
            receiverName = targetAcc?.ownerName,
            amount = amount,
            description = description.ifBlank { "Nạp tiền vào tài khoản" },
            status = if (highValue) TransactionStatus.AWAITING_BIOMETRIC else TransactionStatus.AWAITING_OTP,
            isHighValue = highValue
        )

        val validation = validateTransactionDetailed(tx)
        if (!validation.isValid) {
            securityService.logEvent(
                actorId = tx.userId,
                action = "TX_VALIDATION_FAILED",
                entity = "transactions",
                entityId = tx.transactionId,
                status = "REJECTED",
                details = "Nạp tiền không hợp lệ: ${validation.errorMessage}"
            )
            throw IllegalArgumentException(validation.errorMessage)
        }

        saveTransaction(tx)
        securityService.logEvent(
            actorId = tx.userId,
            action = "TX_INITIATED_DEPOSIT",
            entity = "transactions",
            entityId = tx.transactionId,
            status = tx.status.name,
            details = "Khởi tạo nạp tiền ${FormatAndSecurityUtils.formatVnd(amount)} vào STK ${targetAcc?.accountNumber}"
        )
        return tx
    }

    @Synchronized
    fun processWithdraw(
        srcAccId: String,
        amount: BigDecimal,
        description: String = "Rút tiền về tài khoản liên kết"
    ): Transaction {
        val srcAcc = accountRepository.getAccountById(srcAccId)
        val highValue = amount.compareTo(highValueThreshold) >= 0
        val tx = Transaction(
            transactionId = generateTxId("WDR"),
            userId = srcAcc?.userId ?: "unknown",
            transactionType = TransactionType.WITHDRAW,
            sourceAccountId = srcAccId,
            targetAccountId = null,
            amount = amount,
            description = description.ifBlank { "Rút tiền từ tài khoản" },
            status = if (highValue) TransactionStatus.AWAITING_BIOMETRIC else TransactionStatus.AWAITING_OTP,
            isHighValue = highValue
        )

        val validation = validateTransactionDetailed(tx)
        if (!validation.isValid) {
            securityService.logEvent(
                actorId = tx.userId,
                action = "TX_VALIDATION_FAILED",
                entity = "transactions",
                entityId = tx.transactionId,
                status = "REJECTED",
                details = "Rút tiền không hợp lệ: ${validation.errorMessage}"
            )
            throw IllegalArgumentException(validation.errorMessage)
        }

        saveTransaction(tx)
        securityService.logEvent(
            actorId = tx.userId,
            action = "TX_INITIATED_WITHDRAW",
            entity = "transactions",
            entityId = tx.transactionId,
            status = tx.status.name,
            details = "Khởi tạo rút tiền ${FormatAndSecurityUtils.formatVnd(amount)} từ STK ${srcAcc?.accountNumber}"
        )
        return tx
    }

    @Synchronized
    fun processTransfer(
        srcId: String,
        dstNum: String,
        bankCode: String?,
        amt: BigDecimal,
        description: String = "",
        customReceiverName: String? = null
    ): Transaction {
        val srcAcc = accountRepository.getAccountById(srcId)
        val normalizedBank = bankCode?.trim()?.takeIf { it.isNotEmpty() }
        val isExternal = normalizedBank != null
        val internalTarget = if (!isExternal) accountRepository.getAccountByNumber(dstNum.trim()) else null

        val receiverNameResolved = when {
            !isExternal -> internalTarget?.ownerName
            !customReceiverName.isNullOrBlank() -> customReceiverName.trim()
            else -> ExternalBankDirectory.lookupReceiverName(normalizedBank!!, dstNum) ?: "KHACH HANG ($normalizedBank)"
        }

        val highValue = amt.compareTo(highValueThreshold) >= 0
        val tx = Transaction(
            transactionId = generateTxId(if (isExternal) "EXT" else "TRF"),
            userId = srcAcc?.userId ?: "unknown",
            transactionType = if (isExternal) TransactionType.EXTERNAL_TRANSFER else TransactionType.INTERNAL_TRANSFER,
            sourceAccountId = srcId,
            targetAccountId = internalTarget?.accountId,
            targetAccountNumber = dstNum.trim(),
            targetBankCode = normalizedBank,
            receiverName = receiverNameResolved,
            amount = amt,
            description = description.ifBlank {
                if (isExternal) "Chuyển tiền liên ngân hàng $normalizedBank" else "Chuyển tiền nội bộ"
            },
            status = if (highValue) TransactionStatus.AWAITING_BIOMETRIC else TransactionStatus.AWAITING_OTP,
            isHighValue = highValue
        )

        val validation = validateTransactionDetailed(tx)
        if (!validation.isValid) {
            securityService.logEvent(
                actorId = tx.userId,
                action = "TX_VALIDATION_FAILED",
                entity = "transactions",
                entityId = tx.transactionId,
                status = "REJECTED",
                details = "Chuyển tiền không hợp lệ: ${validation.errorMessage}"
            )
            throw IllegalArgumentException(validation.errorMessage)
        }

        saveTransaction(tx)
        securityService.logEvent(
            actorId = tx.userId,
            action = if (isExternal) "TX_INITIATED_EXTERNAL_TRANSFER" else "TX_INITIATED_INTERNAL_TRANSFER",
            entity = "transactions",
            entityId = tx.transactionId,
            status = tx.status.name,
            details = "Khởi tạo chuyển ${FormatAndSecurityUtils.formatVnd(amt)} tới $dstNum (${normalizedBank ?: "Nội bộ"})"
        )
        return tx
    }

    @Synchronized
    override fun createUtilityPayment(
        userId: String,
        srcAccId: String,
        amt: BigDecimal,
        desc: String
    ): Transaction {
        val highValue = amt.compareTo(highValueThreshold) >= 0
        val tx = Transaction(
            transactionId = generateTxId("UTL"),
            userId = userId,
            transactionType = TransactionType.UTILITY_PAYMENT,
            sourceAccountId = srcAccId,
            amount = amt,
            description = desc,
            status = if (highValue) TransactionStatus.AWAITING_BIOMETRIC else TransactionStatus.AWAITING_OTP,
            isHighValue = highValue
        )
        val validation = validateTransactionDetailed(tx)
        if (!validation.isValid) {
            throw IllegalArgumentException(validation.errorMessage)
        }
        saveTransaction(tx)
        return tx
    }

    @Synchronized
    fun commitTransaction(
        txId: String,
        challengeId: String?,
        bioId: String?
    ): Transaction {
        val tx = transactions[txId]
            ?: throw IllegalArgumentException("Không tìm thấy giao dịch $txId")

        if (tx.isTerminalState()) {
            return tx
        }

        // 1. Re-validate balances and account status at commit time
        val validation = validateTransactionDetailed(tx)
        if (!validation.isValid) {
            return markFailed(tx, validation.errorMessage ?: "Kiểm tra giao dịch thất bại")
        }

        // 2. Verify OTP 2FA challenge
        if (challengeId.isNullOrBlank()) {
            return markFailed(tx, "Chưa hoàn tất xác thực OTP 2FA")
        }
        val challenge = securityService.getChallengeById(challengeId)
        if (challenge == null || challenge.transactionId != tx.transactionId || challenge.status != OtpStatus.VERIFIED || !challenge.isUsed) {
            return markFailed(tx, "Phiên OTP chưa được xác thực hợp lệ cho giao dịch này")
        }

        // 3. Verify Biometrics if high-value transaction
        val requiresBio = tx.isHighValue(highValueThreshold)
        if (requiresBio) {
            if (bioId.isNullOrBlank()) {
                return markFailed(tx, "Giao dịch giá trị cao (>= ${FormatAndSecurityUtils.formatVnd(highValueThreshold)}) bắt buộc xác thực sinh trắc học")
            }
            val bioRecord = securityService.getBiometricVerification(bioId)
            if (bioRecord == null || bioRecord.transactionId != tx.transactionId || !bioRecord.isPassed()) {
                return markFailed(tx, "Xác thực sinh trắc học chưa đạt hoặc không khớp giao dịch")
            }
        }

        // 4. Perform atomic balance update only after all validations and auth checks passed
        val balanceUpdated = when (tx.transactionType) {
            TransactionType.DEPOSIT ->
                accountRepository.updateBalanceAtomic(null, tx.targetAccountId, tx.amount)

            TransactionType.WITHDRAW,
            TransactionType.EXTERNAL_TRANSFER,
            TransactionType.UTILITY_PAYMENT ->
                accountRepository.updateBalanceAtomic(tx.sourceAccountId, null, tx.amount)

            TransactionType.INTERNAL_TRANSFER ->
                accountRepository.updateBalanceAtomic(tx.sourceAccountId, tx.targetAccountId, tx.amount)

            TransactionType.REFUND -> true
        }

        if (!balanceUpdated) {
            return markFailed(tx, "Cập nhật số dư thất bại (số dư hoặc trạng thái tài khoản đã thay đổi)")
        }

        val completedTx = tx.copy(
            status = TransactionStatus.COMPLETED,
            is2faVerified = true,
            isBiometricVerified = requiresBio,
            completedAt = System.currentTimeMillis()
        )
        transactions[txId] = completedTx
        failureReasons.remove(txId)

        securityService.logEvent(
            actorId = tx.userId,
            action = "TX_COMMITTED_SUCCESS",
            entity = "transactions",
            entityId = tx.transactionId,
            status = "COMPLETED",
            details = "Hoàn tất ${tx.transactionType.name}: ${FormatAndSecurityUtils.formatVnd(tx.amount)} (2FA=true, Bio=$requiresBio)"
        )
        return completedTx
    }

    @Synchronized
    fun failTransaction(txId: String, reason: String): Transaction {
        val tx = transactions[txId]
            ?: throw IllegalArgumentException("Không tìm thấy giao dịch $txId")
        if (tx.isTerminalState()) {
            return tx
        }
        return markFailed(tx, reason)
    }

    private fun markFailed(tx: Transaction, reason: String): Transaction {
        val failedTx = tx.copy(
            status = TransactionStatus.FAILED,
            completedAt = System.currentTimeMillis()
        )
        transactions[tx.transactionId] = failedTx
        failureReasons[tx.transactionId] = reason

        securityService.logEvent(
            actorId = tx.userId,
            action = "TX_COMMIT_FAILED",
            entity = "transactions",
            entityId = tx.transactionId,
            status = "FAILED",
            details = "Giao dịch thất bại (số dư không đổi): $reason"
        )
        return failedTx
    }

    @Synchronized
    fun getFailureReason(txId: String): String? = failureReasons[txId]

    @Synchronized
    override fun refundTransaction(txId: String, reason: String): Boolean {
        val original = transactions[txId] ?: return false
        if (original.status != TransactionStatus.COMPLETED) return false

        val reversed = when (original.transactionType) {
            TransactionType.WITHDRAW,
            TransactionType.EXTERNAL_TRANSFER,
            TransactionType.UTILITY_PAYMENT ->
                accountRepository.updateBalanceAtomic(null, original.sourceAccountId, original.amount)

            TransactionType.INTERNAL_TRANSFER ->
                accountRepository.updateBalanceAtomic(original.targetAccountId, original.sourceAccountId, original.amount)

            TransactionType.DEPOSIT ->
                accountRepository.updateBalanceAtomic(original.targetAccountId, null, original.amount)

            TransactionType.REFUND -> false
        }
        if (!reversed) return false

        transactions[txId] = original.copy(status = TransactionStatus.REFUNDED)
        val refundTx = Transaction(
            transactionId = generateTxId("RFD"),
            userId = original.userId,
            transactionType = TransactionType.REFUND,
            sourceAccountId = original.targetAccountId,
            targetAccountId = original.sourceAccountId,
            amount = original.amount,
            description = "Hoàn tiền cho $txId: $reason",
            status = TransactionStatus.COMPLETED,
            is2faVerified = true,
            completedAt = System.currentTimeMillis()
        )
        saveTransaction(refundTx)

        securityService.logEvent(
            actorId = original.userId,
            action = "TX_REFUNDED",
            entity = "transactions",
            entityId = txId,
            status = "REFUNDED",
            details = "Hoàn tiền giao dịch $txId (${FormatAndSecurityUtils.formatVnd(original.amount)}). Lý do: $reason"
        )
        return true
    }

    @Synchronized
    override fun saveTransaction(tx: Transaction): Boolean {
        transactions[tx.transactionId] = tx
        return true
    }

    @Synchronized
    override fun getTransactionById(txId: String): Transaction? = transactions[txId]

    @Synchronized
    override fun getTransactionsByAccount(accId: String): List<Transaction> {
        return transactions.values
            .filter { it.sourceAccountId == accId || it.targetAccountId == accId }
            .sortedByDescending { it.createdAt }
    }

    @Synchronized
    fun getAllTransactions(): List<Transaction> {
        return transactions.values.sortedByDescending { it.createdAt }
    }

    @Synchronized
    override fun updateTransactionStatus(txId: String, status: TransactionStatus): Boolean {
        val existing = transactions[txId] ?: return false
        transactions[txId] = existing.copy(status = status)
        return true
    }

    @Synchronized
    fun clearAll() {
        transactions.clear()
        failureReasons.clear()
    }

    private fun generateTxId(prefix: String): String {
        return "$prefix-${UUID.randomUUID().toString().take(8).uppercase()}"
    }
}

