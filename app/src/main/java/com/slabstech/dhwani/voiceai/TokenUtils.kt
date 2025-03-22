package com.slabstech.dhwani.voiceai

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.preference.PreferenceManager
import org.json.JSONObject
import java.nio.charset.StandardCharsets

object TokenUtils {
    private const val TAG = "TokenUtils"

    fun getTokenExpiration(token: String): Long? {
        return try {
            val payload = token.split(".")[1]
            val decodedBytes = Base64.decode(payload, Base64.URL_SAFE)
            val decodedPayload = String(decodedBytes, StandardCharsets.UTF_8)
            val json = JSONObject(decodedPayload)
            val exp = json.getLong("exp") * 1000
            Log.d(TAG, "Token expiration: $exp")
            exp
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode token: ${e.message}", e)
            null
        }
    }

    fun isTokenExpired(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val token = prefs.getString("access_token", null) ?: return true
        val expiryTime = prefs.getLong("token_expiry_time", 0L)
        val expired = expiryTime < System.currentTimeMillis() || expiryTime == 0L
        Log.d(TAG, "isTokenExpired: $expired, expiryTime: $expiryTime")
        return expired
    }

    suspend fun refreshTokenIfNeeded(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val currentToken = prefs.getString("access_token", null)
        Log.d(TAG, "Checking token: $currentToken")

        if (currentToken == null) {
            Log.d(TAG, "No current token, refresh not possible")
            return false
        }

        if (isTokenExpired(context)) {
            Log.d(TAG, "Token expired, attempting refresh")
            try {
                val response = RetrofitClient.apiService(context).refreshToken("Bearer $currentToken")
                val newToken = response.access_token
                val newExpiryTime = getTokenExpiration(newToken) ?: (System.currentTimeMillis() + 30 * 1000)
                prefs.edit()
                    .putString("access_token", newToken)
                    .putLong("token_expiry_time", newExpiryTime)
                    .apply()
                Log.d(TAG, "Token refreshed successfully: $newToken, expiry: $newExpiryTime")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Token refresh failed: ${e.message}", e)
                return false
            }
        }
        Log.d(TAG, "Token still valid")
        return true
    }
}