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
    private const val EXPIRY_KEY = "token_expiry_time"
    private const val EXPIRY_BUFFER_MS = 5 * 60 * 1000 // 5 minutes
    private const val MAX_RETRIES = 3

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

    suspend fun login(context: Context, email: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.apiService(context).login(LoginRequest(email, email))
            val token = response.access_token
            val expiryTime = getTokenExpiration(token) ?: (System.currentTimeMillis() + 30 * 1000)
            saveToken(context, token, expiryTime)
            Log.d("AuthManager", "Login successful, expiry: $expiryTime")
            true
        } catch (e: Exception) {
            Log.e("AuthManager", "Login failed: ${e.message}", e)
            false
        }
    }

    suspend fun refreshTokenIfNeeded(context: Context): Boolean = withContext(Dispatchers.IO) {
        val prefs = getSecurePrefs(context)
        val currentToken = prefs.getString(TOKEN_KEY, null) ?: return@withContext false

        if (isTokenExpired(context)) {
            var attempts = 0
            while (attempts < MAX_RETRIES) {
                try {
                    val response = RetrofitClient.apiService(context).refreshToken("Bearer $currentToken")
                    val newToken = response.access_token
                    val newExpiryTime = getTokenExpiration(newToken) ?: (System.currentTimeMillis() + 30 * 1000)
                    saveToken(context, newToken, newExpiryTime)
                    Log.d("AuthManager", "Token refreshed successfully")
                    return@withContext true
                } catch (e: Exception) {
                    Log.e("AuthManager", "Token refresh attempt ${attempts + 1} failed: ${e.message}", e)
                    attempts++
                    if (attempts < MAX_RETRIES) {
                        delay(1000L * attempts) // Exponential backoff
                    }
                }
            }
            return@withContext false // All retries failed
        }
        true
    }

    fun isAuthenticated(context: Context): Boolean {
        return getSecurePrefs(context).contains(TOKEN_KEY)
    }

    fun getToken(context: Context): String? {
        return getSecurePrefs(context).getString(TOKEN_KEY, null)
    }

    fun logout(context: Context) {
        getSecurePrefs(context).edit()
            .remove(TOKEN_KEY)
            .remove(EXPIRY_KEY)
            .apply()
        context.startActivity(Intent(context, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
    }

    private fun isTokenExpired(context: Context): Boolean {
        val prefs = getSecurePrefs(context)
        val expiryTime = prefs.getLong(EXPIRY_KEY, 0L)
        return (expiryTime - EXPIRY_BUFFER_MS) < System.currentTimeMillis() || expiryTime == 0L
    }

    internal fun saveToken(context: Context, token: String, expiryTime: Long) {
        getSecurePrefs(context).edit()
            .putString(TOKEN_KEY, token)
            .putLong(EXPIRY_KEY, expiryTime)
            .apply()
    }

    internal fun getTokenExpiration(token: String): Long? {
        return try {
            val payload = token.split(".")[1]
            val decodedBytes = Base64.decode(payload, Base64.URL_SAFE)
            val decodedPayload = String(decodedBytes, StandardCharsets.UTF_8)
            val json = JSONObject(decodedPayload)
            (json.getLong("exp") * 1000)
        } catch (e: Exception) {
            Log.e("AuthManager", "Failed to decode token: ${e.message}", e)
            null
        }
    }
}