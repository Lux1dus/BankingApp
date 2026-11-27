package com.example.bankingapp.features.corebanking

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.bankingapp.R

class CustomerDashboardActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_customer_dashboard)

        val user = AuthService.currentUser
        if (user == null) {
            goToLogin()
            return
        }

        findViewById<TextView>(R.id.txtWelcome).text = "Xin chào, ${user.fullName}"

        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            AuthService.logout()
            goToLogin()
        }
    }

    private fun goToLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}