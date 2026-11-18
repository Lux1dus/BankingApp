package com.example.bankingapp.common.model

data class User(
    val userId: String,
    val username: String,
    val passwordHash: String,
    val fullName: String,
    val email: String,
    val phoneNumber: String,
    val role: UserRole,
    val status: UserStatus,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun isActive(): Boolean = status == UserStatus.ACTIVE

    fun isOfficer(): Boolean = role == UserRole.BANK_OFFICER
}

