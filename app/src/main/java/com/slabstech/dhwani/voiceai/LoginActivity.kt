package com.slabstech.dhwani.voiceai

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class LoginActivity : AppCompatActivity() {
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private val TAG = "LoginActivity"
    private val DEVICE_TOKEN_KEY = "device_token"
    private val SESSION_KEY = "session_key"

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

        // Generate or retrieve session key
        var sessionKey = prefs.getString(SESSION_KEY, null)?.let { encodedKey ->
            try {
                val cleanKey = encodedKey.trim()
                if (!isValidBase64(cleanKey)) {
                    throw IllegalArgumentException("Invalid Base64 format for session key")
                }
                Base64.decode(cleanKey, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Invalid session key format: ${e.message}")
                null
            }
        }
        if (sessionKey == null) {
            sessionKey = ByteArray(16).apply { SecureRandom().nextBytes(this) }
            val encodedKey = Base64.encodeToString(sessionKey, Base64.NO_WRAP)
            prefs.edit().putString(SESSION_KEY, encodedKey).apply()
            Log.d(TAG, "Generated new session key: $encodedKey")
        } else {
            Log.d(TAG, "Using existing session key")
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
                loginOrRegister(email, deviceToken!!, sessionKey!!)
            }
        }
    }

    private fun loginOrRegister(email: String, deviceToken: String, sessionKey: ByteArray) {
        lifecycleScope.launch {
            Log.d(TAG, "Attempting login with email: $email")
            try {
                if (AuthManager.login(this@LoginActivity, email, deviceToken, sessionKey)) {
                    Log.d(TAG, "Login successful")
                    Toast.makeText(this@LoginActivity, "Login successful", Toast.LENGTH_SHORT).show()
                    proceedToVoiceDetectionActivity()
                } else {
                    Log.d(TAG, "Login failed, attempting app registration")
                    if (AuthManager.appRegister(this@LoginActivity, email, deviceToken, sessionKey)) {
                        Log.d(TAG, "App registration successful, retrying login")
                        if (AuthManager.login(this@LoginActivity, email, deviceToken, sessionKey)) {
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

    private fun proceedToVoiceDetectionActivity() {
        Log.d(TAG, "Proceeding to VoiceDetectionActivity")
        startActivity(Intent(this@LoginActivity, VoiceDetectionActivity::class.java))
        finish()
    }

    private fun isValidBase64(str: String): Boolean {
        return str.matches(Regex("^[A-Za-z0-9+/=]+$"))
    }

    override fun onRestart() {
        super.onRestart()
        Log.d(TAG, "onRestart: Activity restarted")
    }
}