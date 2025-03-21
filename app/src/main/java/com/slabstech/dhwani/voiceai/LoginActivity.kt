package com.slabstech.dhwani.voiceai

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        val isDarkTheme = prefs.getBoolean("dark_theme", false)
        setTheme(if (isDarkTheme) R.style.Theme_DhwaniVoiceAI_Dark else R.style.Theme_DhwaniVoiceAI_Light)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val emailEditText = findViewById<EditText>(R.id.emailEditText)
        val loginButton = findViewById<Button>(R.id.loginButton)

        lifecycleScope.launch {
            if (AuthManager.isAuthenticated(this@LoginActivity) && AuthManager.refreshTokenIfNeeded(this@LoginActivity)) {
                startActivity(Intent(this@LoginActivity, AnswerActivity::class.java))
                finish()
                return@launch
            }
        }

        loginButton.setOnClickListener {
            val email = emailEditText.text.toString().trim()
            if (email.isEmpty()) {
                Toast.makeText(this, "Please enter your email", Toast.LENGTH_SHORT).show()
            } else if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                Toast.makeText(this, "Please enter a valid email", Toast.LENGTH_SHORT).show()
            } else {
                fetchAccessToken(email)
            }
        }
    }

    private fun fetchAccessToken(email: String) {
        lifecycleScope.launch {
            if (AuthManager.login(this@LoginActivity, email)) {
                Toast.makeText(this@LoginActivity, "Login successful", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this@LoginActivity, AnswerActivity::class.java))
                finish()
            } else {
                Toast.makeText(this@LoginActivity, "Login failed", Toast.LENGTH_LONG).show()
            }
        }
    }
}