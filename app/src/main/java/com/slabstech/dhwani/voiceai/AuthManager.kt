package com.slabstech.dhwani.voiceai

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.nio.charset.StandardCharsets

object AuthManager {
    private const val TOKEN_KEY = "access_token"
    private const val REFRESH_TOKEN_KEY = "refresh_token"
    private const val EXPIRY_KEY = "token_expiry_time"
    private const val EXPIRY_BUFFER_MS = 60 * 60 * 1000 // 1 hour buffer
    private const val MAX_RETRIES = 3
    private const val TAG = "AuthManager"

    private fun getSecurePrefs(context: Context): SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            "secure_prefs",
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    suspend fun login(context: Context, email: String, deviceToken: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Attempting login for email: $email")
            val response = RetrofitClient.apiService(context).login(LoginRequest(email, deviceToken))
            val token = response.access_token
            val refreshToken = response.refresh_token
            val expiryTime = getTokenExpiration(token) ?: (System.currentTimeMillis() + 24 * 60 * 60 * 1000) // 24 hours
            saveTokens(context, token, refreshToken, expiryTime)
            Log.d(TAG, "Login successful, expiry: $expiryTime")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Login failed: ${e.message}", e)
            false
        }
    }

    suspend fun appRegister(context: Context, email: String, deviceToken: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Attempting app registration for email: $email")
            val response = RetrofitClient.apiService(context).appRegister(RegisterRequest(email, deviceToken))
            val token = response.access_token
            val refreshToken = response.refresh_token
            val expiryTime = getTokenExpiration(token) ?: (System.currentTimeMillis() + 24 * 60 * 60 * 1000) // 24 hours
            saveTokens(context, token, refreshToken, expiryTime)
            Log.d(TAG, "App registration successful, expiry: $expiryTime")
            true
        } catch (e: Exception) {
            Log.e(TAG, "App registration failed: ${e.message}", e)
            false
        }
    }

    suspend fun refreshTokenIfNeeded(context: Context): Boolean = withContext(Dispatchers.IO) {
        val prefs = getSecurePrefs(context)
        val currentToken = prefs.getString(TOKEN_KEY, null)
        val refreshToken = prefs.getString(REFRESH_TOKEN_KEY, null)

        Log.d(TAG, "Checking token state - Token exists: ${currentToken != null}, RefreshToken exists: ${refreshToken != null}")

        if (currentToken == null || refreshToken == null) {
            Log.d(TAG, "No tokens available, refresh not possible")
            return@withContext false
        }

        if (isTokenExpired(context)) {
            Log.d(TAG, "Token expired, attempting refresh")
            var attempts = 0
            while (attempts < MAX_RETRIES) {
                try {
                    val response = RetrofitClient.apiService(context).refreshToken("Bearer $refreshToken")
                    val newToken = response.access_token
                    val newRefreshToken = response.refresh_token
                    val newExpiryTime = getTokenExpiration(newToken) ?: (System.currentTimeMillis() + 24 * 60 * 60 * 1000)
                    saveTokens(context, newToken, newRefreshToken, newExpiryTime)
                    Log.d(TAG, "Token refreshed successfully")
                    return@withContext true
                } catch (e: Exception) {
                    Log.e(TAG, "Token refresh attempt ${attempts + 1} failed: ${e.message}", e)
                    attempts++
                    if (attempts < MAX_RETRIES) {
                        delay(1000L * attempts) // Exponential backoff
                    }
                }
            }
            Log.e(TAG, "All refresh attempts failed")
            return@withContext false
        }
        Log.d(TAG, "Token still valid")
        true
    }

    fun isAuthenticated(context: Context): Boolean {
        val prefs = getSecurePrefs(context)
        val hasTokens = prefs.contains(TOKEN_KEY) && prefs.contains(REFRESH_TOKEN_KEY)
        Log.d(TAG, "isAuthenticated: $hasTokens")
        return hasTokens
    }

    fun getToken(context: Context): String? {
        return getSecurePrefs(context).getString(TOKEN_KEY, null)
    }

    fun getRefreshToken(context: Context): String? {
        return getSecurePrefs(context).getString(REFRESH_TOKEN_KEY, null)
    }

    fun logout(context: Context) {
        Log.d(TAG, "Logging out")
        getSecurePrefs(context).edit()
            .remove(TOKEN_KEY)
            .remove(REFRESH_TOKEN_KEY)
            .remove(EXPIRY_KEY)
            .apply()
        val intent = Intent(context, LoginActivity::class.java).apply {
            putExtra("from_logout", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        context.startActivity(intent)
    }

    private fun isTokenExpired(context: Context): Boolean {
        val prefs = getSecurePrefs(context)
        val expiryTime = prefs.getLong(EXPIRY_KEY, 0L)
        val expired = (expiryTime - EXPIRY_BUFFER_MS) < System.currentTimeMillis() || expiryTime == 0L
        Log.d(TAG, "isTokenExpired: $expired, expiryTime: $expiryTime")
        return expired
    }

    internal fun saveTokens(context: Context, token: String, refreshToken: String, expiryTime: Long) {
        Log.d(TAG, "Saving tokens - Expiry: $expiryTime")
        getSecurePrefs(context).edit()
            .putString(TOKEN_KEY, token)
            .putString(REFRESH_TOKEN_KEY, refreshToken)
            .putLong(EXPIRY_KEY, expiryTime)
            .apply()
    }

    internal fun getTokenExpiration(token: String): Long? {
        return try {
            val payload = token.split(".")[1]
            val decodedBytes = Base64.decode(payload, Base64.URL_SAFE)
            val decodedPayload = String(decodedBytes, StandardCharsets.UTF_8)
            val json = JSONObject(decodedPayload)
            val exp = json.getLong("exp") * 1000
            Log.d(TAG, "Token expiration decoded: $exp")
            exp
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode token: ${e.message}", e)
            null
        }
    }
}