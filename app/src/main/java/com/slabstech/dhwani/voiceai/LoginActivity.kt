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
import java.util.UUID

class LoginActivity : AppCompatActivity() {
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private val TAG = "LoginActivity"
    private val DEVICE_TOKEN_KEY = "device_token"

    override fun onCreate(savedInstanceState: Bundle?) {
        val isDarkTheme = prefs.getBoolean("dark_theme", false)
        setTheme(if (isDarkTheme) R.style.Theme_DhwaniVoiceAI_Dark else R.style.Theme_DhwaniVoiceAI_Light)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        Log.d(TAG, "onCreate: Starting LoginActivity")

        val emailEditText = findViewById<EditText>(R.id.emailEditText)
        val loginButton = findViewById<Button>(R.id.loginButton)

        // Generate or retrieve device token
        var deviceToken = prefs.getString(DEVICE_TOKEN_KEY, null)
        if (deviceToken == null) {
            deviceToken = UUID.randomUUID().toString()
            prefs.edit().putString(DEVICE_TOKEN_KEY, deviceToken).apply()
            Log.d(TAG, "Generated new device token")
        } else {
            Log.d(TAG, "Using existing device token")
        }

        val fromLogout = intent.getBooleanExtra("from_logout", false)
        Log.d(TAG, "fromLogout: $fromLogout")

        if (!fromLogout && savedInstanceState == null) {
            lifecycleScope.launch {
                Log.d(TAG, "Checking authentication state")
                if (AuthManager.isAuthenticated(this@LoginActivity)) {
                    val tokenRefreshed = AuthManager.refreshTokenIfNeeded(this@LoginActivity)
                    Log.d(TAG, "Token refresh result: $tokenRefreshed")
                    if (tokenRefreshed) {
                        Log.d(TAG, "Token valid, proceeding to VoiceDetectionActivity")
                        proceedToVoiceDetectionActivity()
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
                loginOrRegister(email, deviceToken!!)
            }
        }
    }

    private fun loginOrRegister(email: String, deviceToken: String) {
        lifecycleScope.launch {
            Log.d(TAG, "Attempting login with email: $email")
            try {
                if (AuthManager.login(this@LoginActivity, email, deviceToken)) {
                    Log.d(TAG, "Login successful")
                    Toast.makeText(this@LoginActivity, "Login successful", Toast.LENGTH_SHORT).show()
                    proceedToVoiceDetectionActivity()
                } else {
                    Log.d(TAG, "Login failed, attempting app registration")
                    if (appRegister(email, deviceToken)) {
                        Log.d(TAG, "App registration successful, retrying login")
                        if (AuthManager.login(this@LoginActivity, email, deviceToken)) {
                            Toast.makeText(this@LoginActivity, "Account created and logged in", Toast.LENGTH_SHORT).show()
                            proceedToVoiceDetectionActivity()
                        } else {
                            Log.e(TAG, "Login failed after app registration")
                            Toast.makeText(this@LoginActivity, "Failed to log in after account creation. Please try again.", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        Log.e(TAG, "App registration failed")
                        Toast.makeText(this@LoginActivity, "Unable to create account. Email may already be registered.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Authentication error: ${e.message}", e)
                Toast.makeText(this@LoginActivity, "Connection error: Unable to reach server.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun appRegister(email: String, deviceToken: String): Boolean {
        try {
            val apiService = RetrofitClient.apiService(this)
            val registerRequest = RegisterRequest(username = email, password = deviceToken)
            val response = apiService.appRegister(registerRequest)
            // Use default expiry if getTokenExpiration returns null
            val expiryTime = AuthManager.getTokenExpiration(response.access_token)
                ?: (System.currentTimeMillis() + 24 * 60 * 60 * 1000) // 24 hours
            AuthManager.saveTokens(this, response.access_token, response.refresh_token, expiryTime)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "App registration failed: ${e.message}", e)
            return false
        }
    }

    private fun proceedToVoiceDetectionActivity() {
        Log.d(TAG, "Proceeding to VoiceDetectionActivity")
        startActivity(Intent(this@LoginActivity, VoiceDetectionActivity::class.java))
        finish()
    }

    override fun onRestart() {
        super.onRestart()
        Log.d(TAG, "onRestart: Activity restarted")
    }
}