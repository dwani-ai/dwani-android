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

class LoginActivity : AppCompatActivity() {
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private val TAG = "LoginActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        val isDarkTheme = prefs.getBoolean("dark_theme", false)
        setTheme(if (isDarkTheme) R.style.Theme_DhwaniVoiceAI_Dark else R.style.Theme_DhwaniVoiceAI_Light)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        Log.d(TAG, "onCreate: Starting LoginActivity")

        val emailEditText = findViewById<EditText>(R.id.emailEditText)
        val loginButton = findViewById<Button>(R.id.loginButton)

        val fromLogout = intent.getBooleanExtra("from_logout", false)
        Log.d(TAG, "fromLogout: $fromLogout")

        if (!fromLogout && savedInstanceState == null) { // Only check on fresh start
            lifecycleScope.launch {
                Log.d(TAG, "Checking authentication state")
                if (AuthManager.isAuthenticated(this@LoginActivity)) {
                    val tokenRefreshed = AuthManager.refreshTokenIfNeeded(this@LoginActivity)
                    Log.d(TAG, "Token refresh result: $tokenRefreshed")
                    if (tokenRefreshed) {
                        Log.d(TAG, "Token valid, proceeding to AnswerActivity")
                        proceedToAnswerActivity()
                    } else {
                        Log.d(TAG, "Token refresh failed, staying on LoginActivity")
                        Toast.makeText(this@LoginActivity, "Session expired. Please log in again.", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Log.d(TAG, "Not authenticated, staying on LoginActivity")
                }
            }
        } else {
            Log.d(TAG, "Skipping auth check due to logout or restart")
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
            Log.d(TAG, "Attempting login with email: $email")
            if (AuthManager.login(this@LoginActivity, email)) {
                Log.d(TAG, "Login successful")
                Toast.makeText(this@LoginActivity, "Login successful", Toast.LENGTH_SHORT).show()
                proceedToAnswerActivity()
            } else {
                Log.e(TAG, "Login failed")
                Toast.makeText(this@LoginActivity, "Login failed. Please try again.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun proceedToAnswerActivity() {
        Log.d(TAG, "Proceeding to AnswerActivity")
        startActivity(Intent(this@LoginActivity, VoiceDetectionActivity::class.java))
        finish()
    }

    override fun onRestart() {
        super.onRestart()
        Log.d(TAG, "onRestart: Activity restarted")
    }
}