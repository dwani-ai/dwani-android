package com.slabstech.dhwani.voiceai

import android.app.Application
import android.content.Context
import androidx.preference.PreferenceManager
import com.google.gson.GsonBuilder
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

// Data Models
data class LoginRequest(val username: String, val password: String)
data class TokenResponse(val access_token: String, val token_type: String)
data class TranscriptionResponse(val text: String)
data class ChatRequest(val prompt: String, val src_lang: String, val tgt_lang: String)
data class ChatResponse(val response: String)
data class TTSRequest(val input: String, val voice: String, val model: String, val response_format: String, val speed: Double)
data class TranslationRequest(val sentences: List<String>, val src_lang: String, val tgt_lang: String)
data class TranslationResponse(val translations: List<String>)
data class VisualQueryResponse(val answer: String)

// API Service Interface
interface ApiService {
    @POST("v1/token")
    suspend fun login(@Body loginRequest: LoginRequest): TokenResponse

    @POST("v1/refresh")
    suspend fun refreshToken(@Header("Authorization") token: String): TokenResponse

    @Multipart
    @POST("v1/transcribe/")
    suspend fun transcribeAudio(
        @Part file: MultipartBody.Part,
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
        @Body ttsRequest: TTSRequest,
        @Header("Authorization") token: String
    ): ResponseBody

    @POST("v1/translate")
    suspend fun translate(
        @Body request: TranslationRequest,
        @Header("Authorization") token: String
    ): TranslationResponse

    @Multipart
    @POST("v1/visual_query/")
    suspend fun visualQuery(
        @Part file: MultipartBody.Part,
        @Part("query") query: RequestBody,
        @Header("Authorization") token: String
    ): VisualQueryResponse
}

// Retrofit Client
object RetrofitClient {
    private const val BASE_URL = "https://slabstech-dhwani-server.hf.space/"
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(DhwaniApp.context) }

    private val authenticator = object : okhttp3.Authenticator {
        override fun authenticate(route: okhttp3.Route?, response: okhttp3.Response): okhttp3.Request? {
            val currentToken = prefs.getString("access_token", null) ?: return null
            val refreshResponse = runBlocking {
                apiService.refreshToken("Bearer $currentToken")
            }
            val newToken = refreshResponse.access_token
            prefs.edit().putString("access_token", newToken).apply()
            return response.request.newBuilder()
                .header("Authorization", "Bearer $newToken")
                .build()
        }
    }

    private val okHttpClient = OkHttpClient.Builder()
        .authenticator(authenticator)
        .addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val apiService: ApiService = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create(GsonBuilder().setLenient().create()))
        .build()
        .create(ApiService::class.java)
}

// Application Class
class DhwaniApp : Application() {
    override fun onCreate() {
        super.onCreate()
        context = this
    }

    companion object {
        lateinit var context: Context
    }
}