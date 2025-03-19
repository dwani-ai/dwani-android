package com.slabstech.dhwani.voiceai

import android.app.Application
import android.content.Context
import androidx.preference.PreferenceManager
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    private const val BASE_URL_DEFAULT = "https://slabstech-dhwani-internal-api-server.hf.space/"
    private const val ASR_BASE_URL = "https://slabstech-asr-indic-server-cpu.hf.space/"

    // Single OkHttpClient without authentication
    private fun getOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY))
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "DhwaniVoiceAI/1.0.3") // Consistent User-Agent
                    .build()
                chain.proceed(request)
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // API service for general calls (chat, translate, TTS, visual query)
    fun apiService(context: Context): ApiService {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val baseUrl = prefs.getString("base_url", BASE_URL_DEFAULT) ?: BASE_URL_DEFAULT
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(getOkHttpClient())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    // API service specifically for transcription
    fun transcriptionApiService(context: Context): ApiService {
        return Retrofit.Builder()
            .baseUrl(ASR_BASE_URL)
            .client(getOkHttpClient())
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