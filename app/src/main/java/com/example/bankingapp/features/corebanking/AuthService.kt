package com.example.bankingapp.features.corebanking

import com.example.bankingapp.common.model.User
import com.example.bankingapp.common.model.UserRole
import com.example.bankingapp.common.model.UserStatus
import java.security.MessageDigest

object AuthService {
    private val users = mutableListOf(
        User(
            userId = "user_cust_01",
            username = "customer",
            passwordHash = hash("123456"),
            fullName = "Nguyễn Văn An",
            email = "an@demo.vn",
            phoneNumber = "0901000001",
            role = UserRole.CUSTOMER,
            status = UserStatus.ACTIVE
        ),
        User(
            userId = "user_officer_01",
            username = "officer",
            passwordHash = hash("123456"),
            fullName = "Lê Thu Hà",
            email = "ha.officer@bank.vn",
            phoneNumber = "0901000002",
            role = UserRole.BANK_OFFICER,
            status = UserStatus.ACTIVE
        )
    )

    var currentUser: User? = null
        private set

    fun login(username: String, rawPwd: String): User? {
        val user = users.find { it.username == username && it.passwordHash == hash(rawPwd) }
        currentUser = user
        return user
    }

    fun logout() {
        currentUser = null
    }

    fun getUserById(userId: String): User? = users.find { it.userId == userId }

    private fun hash(raw: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
}