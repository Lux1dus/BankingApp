package com.example.bankingapp.features.corebanking

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.bankingapp.R
import com.example.bankingapp.common.model.UserRole

class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val edtUsername = findViewById<EditText>(R.id.edtUsername)
        val edtPassword = findViewById<EditText>(R.id.edtPassword)
        val btnLogin = findViewById<Button>(R.id.btnLogin)

        btnLogin.setOnClickListener {
            val username = edtUsername.text.toString().trim()
            val password = edtPassword.text.toString()

            // Kiểm tra đã nhập đủ chưa
            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Vui lòng nhập đầy đủ tên đăng nhập và mật khẩu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Nhờ AuthService kiểm tra
            val user = AuthService.login(username, password)
            if (user == null) {
                Toast.makeText(this, "Sai tên đăng nhập hoặc mật khẩu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Chọn Dashboard theo vai trò
            val nextScreen = when (user.role) {
                UserRole.CUSTOMER -> CustomerDashboardActivity::class.java
                UserRole.BANK_OFFICER -> OfficerDashboardActivity::class.java
            }
            startActivity(Intent(this, nextScreen))
            finish()
        }
    }
}