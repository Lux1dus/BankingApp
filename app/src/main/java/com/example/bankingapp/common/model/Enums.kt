package com.example.bankingapp.common.model

enum class UserRole {
    CUSTOMER,
    BANK_OFFICER
}

enum class UserStatus {
    ACTIVE,
    INACTIVE,
    LOCKED,
    PENDING_EKYC
}

enum class AccountType {
    CHECKING,
    SAVING,
    MORTGAGE
}

enum class AccountStatus {
    ACTIVE,
    FROZEN,
    CLOSED
}

enum class TransactionType {
    DEPOSIT,
    WITHDRAW,
    INTERNAL_TRANSFER,
    EXTERNAL_TRANSFER,
    UTILITY_PAYMENT,
    REFUND
}

enum class TransactionStatus {
    PENDING,
    AWAITING_OTP,
    AWAITING_BIOMETRIC,
    COMPLETED,
    FAILED,
    CANCELLED,
    REFUNDED
}

