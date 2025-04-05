package com.slabstech.dhwani.voiceai

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import kotlinx.coroutines.runBlocking
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

data class LoginRequest(val username: String, val password: String)
data class TokenResponse(val access_token: String, val refresh_token: String, val token_type: String)
data class TranscriptionRequest(val language: String)
data class TranscriptionResponse(val text: String)
data class ChatRequest(val prompt: String, val src_lang: String, val tgt_lang: String)
data class ChatResponse(val response: String)
data class TranslationRequest(val sentences: List<String>, val src_lang: String, val tgt_lang: String)
data class TranslationResponse(val translations: List<String>)
data class VisualQueryResponse(val answer: String)

interface ApiService {
    @POST("v1/token")
    suspend fun login(@Body loginRequest: LoginRequest): TokenResponse

    @Multipart
    @POST("v1/transcribe/")
    suspend fun transcribeAudio(
        @Part audio: MultipartBody.Part,
        @Query("language") language: String,
        @Header("Authorization") token: String
    ): TranscriptionResponse

    @POST("v1/chat")
    suspend fun chat(
        @Body chatRequest: ChatRequest,
        @Header("Authorization") token: String
    ): ChatResponse

    @POST("v1/audio/speech")
    suspend fun textToSpeech(
        @Query("input") input: String,
        @Query("voice") voice: String,
        @Query("model") model: String,
        @Query("response_format") responseFormat: String,
        @Query("speed") speed: Double,
        @Header("Authorization") token: String
    ): ResponseBody

    @POST("v1/translate")
    suspend fun translate(
        @Body translationRequest: TranslationRequest,
        @Header("Authorization") token: String
    ): TranslationResponse

    @Multipart
    @POST("v1/visual_query")
    suspend fun visualQuery(
        @Part file: MultipartBody.Part,
        @Part("query") query: RequestBody,
        @Query("src_lang") srcLang: String,
        @Query("tgt_lang") tgtLang: String,
        @Header("Authorization") token: String
    ): VisualQueryResponse

    @Multipart
    @POST("v1/speech_to_speech")
    suspend fun speechToSpeech(
        @Query("language") language: String,
        @Part file: MultipartBody.Part,
        @Part("voice") voice: RequestBody,
        @Header("Authorization") token: String
    ): ResponseBody

    @POST("v1/refresh")
    suspend fun refreshToken(@Header("Authorization") token: String): TokenResponse
}

object RetrofitClient {
    private const val BASE_URL_DEFAULT = "https://slabstech-dhwani-server.hf.space/"

    private fun getOkHttpClient(context: Context): OkHttpClient {
        val authenticator = object : okhttp3.Authenticator {
            override fun authenticate(route: okhttp3.Route?, response: okhttp3.Response): okhttp3.Request? {
                val refreshToken = AuthManager.getRefreshToken(context) ?: return null
                try {
                    val refreshResponse = runBlocking {
                        apiService(context).refreshToken("Bearer $refreshToken")
                    }
                    val newToken = refreshResponse.access_token
                    val newExpiryTime = AuthManager.getTokenExpiration(newToken) ?: (System.currentTimeMillis() + 24 * 60 * 60 * 1000)
                    AuthManager.saveTokens(context, newToken, refreshResponse.refresh_token, newExpiryTime)
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

    fun apiService(context: Context): ApiService {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val baseUrl = prefs.getString("base_url", BASE_URL_DEFAULT) ?: BASE_URL_DEFAULT
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(getOkHttpClient(context))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}