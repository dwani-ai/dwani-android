package com.slabstech.dhwani.voiceai

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.preference.PreferenceManager
import org.json.JSONObject
import java.nio.charset.StandardCharsets

object TokenUtils {
    fun getTokenExpiration(token: String): Long? {
        return try {
            val payload = token.split(".")[1] // Get the payload part of JWT
            val decodedBytes = Base64.decode(payload, Base64.URL_SAFE)
            val decodedPayload = String(decodedBytes, StandardCharsets.UTF_8)
            val json = JSONObject(decodedPayload)
            (json.getLong("exp") * 1000) // Convert seconds to milliseconds
        } catch (e: Exception) {
            Log.e("TokenUtils", "Failed to decode token: ${e.message}", e)
            null
        }
    }

    fun isTokenExpired(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val token = prefs.getString("access_token", null) ?: return true
        val expiryTime = prefs.getLong("token_expiry_time", 0L)
        return expiryTime < System.currentTimeMillis() || expiryTime == 0L
    }

    suspend fun refreshTokenIfNeeded(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val currentToken = prefs.getString("access_token", null) ?: return false

        if (isTokenExpired(context)) {
            try {
                val response = RetrofitClient.apiService(context).refreshToken("Bearer $currentToken")
                val newToken = response.access_token
                val newExpiryTime = getTokenExpiration(newToken) ?: (System.currentTimeMillis() + 30 * 1000) // Fallback: 30 seconds
                prefs.edit()
                    .putString("access_token", newToken)
                    .putLong("token_expiry_time", newExpiryTime)
                    .apply()
                Log.d("TokenUtils", "Token refreshed successfully")
                return true
            } catch (e: Exception) {
                Log.e("TokenUtils", "Token refresh failed: ${e.message}", e)
                return false
            }
        }
        return true // Token is still valid
    }
}