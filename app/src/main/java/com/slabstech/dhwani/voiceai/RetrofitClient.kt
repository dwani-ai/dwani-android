package com.slabstech.dhwani.voiceai

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    private const val BASE_URL_DEFAULT = "https://slabstech-dhwani-server.hf.space/"

    // Basic OkHttpClient for login (no authenticator)
    private fun getBasicOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // Authenticated OkHttpClient for other calls
    private fun getAuthenticatedOkHttpClient(context: Context, apiService: ApiService): OkHttpClient {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val authenticator = object : okhttp3.Authenticator {
            override fun authenticate(route: okhttp3.Route?, response: okhttp3.Response): okhttp3.Request? {
                val currentToken = prefs.getString("access_token", null) ?: return null
                try {
                    val refreshResponse = runBlocking {
                        apiService.refreshToken("Bearer $currentToken")
                    }
                    val newToken = refreshResponse.access_token
                    prefs.edit().putString("access_token", newToken).apply()
                    return response.request.newBuilder()
                        .header("Authorization", "Bearer $newToken")
                        .build()
                } catch (e: Exception) {
                    Log.e("RetrofitClient", "Token refresh failed: ${e.message}", e)
                    return null
                }
            }
        }

        return OkHttpClient.Builder()
            .authenticator(authenticator)
            .addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // Login-specific API service (no authenticator)
    fun loginApiService(context: Context): ApiService {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val baseUrl = prefs.getString("base_url", BASE_URL_DEFAULT) ?: BASE_URL_DEFAULT
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(getBasicOkHttpClient())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    // General API service for authenticated calls
    fun apiService(context: Context): ApiService {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val baseUrl = prefs.getString("base_url", BASE_URL_DEFAULT) ?: BASE_URL_DEFAULT
        val basicApiService = loginApiService(context) // Use basic service for refresh
        val authenticatedClient = getAuthenticatedOkHttpClient(context, basicApiService)
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(authenticatedClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}

class DhwaniApp : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}