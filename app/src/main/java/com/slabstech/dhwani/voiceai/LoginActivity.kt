package com.slabstech.dhwani.voiceai

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.launch
import retrofit2.Response

class LoginActivity : AppCompatActivity() {
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        val isDarkTheme = prefs.getBoolean("dark_theme", false)
        setTheme(if (isDarkTheme) R.style.Theme_DhwaniVoiceAI_Dark else R.style.Theme_DhwaniVoiceAI_Light)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val emailEditText = findViewById<EditText>(R.id.emailEditText)
        val loginButton = findViewById<Button>(R.id.loginButton)

        // Check if token already exists
        if (prefs.contains("access_token")) {
            startActivity(Intent(this, AnswerActivity::class.java))
            finish()
            return
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
            try {
                val response: Response<TokenResponse> = RetrofitClient.loginApiService(this@LoginActivity).login(LoginRequest(email, email))
                if (response.isSuccessful) {
                    val tokenResponse = response.body()
                    if (tokenResponse != null) {
                        prefs.edit().putString("access_token", tokenResponse.access_token).apply()
                        Toast.makeText(this@LoginActivity, "Login successful", Toast.LENGTH_SHORT).show()
                        startActivity(Intent(this@LoginActivity, AnswerActivity::class.java))
                        finish()
                    } else {
                        Log.e("LoginActivity", "Response body is null")
                        Toast.makeText(this@LoginActivity, "Login failed: Empty response", Toast.LENGTH_LONG).show()
                    }
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e("LoginActivity", "Error response: $errorBody, Code: ${response.code()}")
                    Toast.makeText(this@LoginActivity, "Login failed: ${response.message()}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("LoginActivity", "Login failed", e)
                Toast.makeText(this@LoginActivity, "Login failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}