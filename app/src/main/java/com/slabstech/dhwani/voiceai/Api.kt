package com.slabstech.dhwani.voiceai

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

data class LoginRequest(val username: String, val password: String)
data class RegisterRequest(val username: String, val password: String)
data class TokenResponse(val access_token: String, val refresh_token: String, val token_type: String)
data class TranscriptionRequest(val language: String)
data class TranscriptionResponse(val text: String)
data class ChatRequest(val prompt: String, val src_lang: String, val tgt_lang: String)
data class ChatResponse(val response: String)
data class TranslationRequest(val sentences: List<String>, val src_lang: String, val tgt_lang: String)
data class TranslationResponse(val translations: List<String>)
data class VisualQueryRequest(val query: String) // Simplified: src_lang and tgt_lang moved to query parameters
data class VisualQueryResponse(val answer: String)
data class ExtractTextResponse(val page_content: String)
data class DocumentSummaryResponse(val pages: List<Page>, val summary: String)
data class Page(val page_number: Int, val page_text: String)

interface ApiService {
    @POST("v1/token")
    suspend fun login(
        @Body loginRequest: LoginRequest,
        @Header("X-API-Key") apiKey: String
    ): TokenResponse

    @POST("v1/app/register")
    suspend fun appRegister(
        @Body registerRequest: RegisterRequest,
        @Header("X-API-Key") apiKey: String
    ): TokenResponse

    @Multipart
    @POST("v1/transcribe/")
    suspend fun transcribeAudio(
        @Part audio: MultipartBody.Part,
        @Query("language") language: String,
        @Header("X-API-Key") apiKey: String
    ): TranscriptionResponse

    @Headers("Accept: application/json")
    @POST("v1/indic_chat")
    suspend fun chat(
        @Body chatRequest: ChatRequest,
        @Header("X-API-Key") apiKey: String
    ): ChatResponse

    @POST("v1/audio/speech")
    suspend fun textToSpeech(
        @Query("input") input: String,
        @Header("X-API-Key") apiKey: String
    ): ResponseBody

    @POST("v1/translate")
    suspend fun translate(
        @Body translationRequest: TranslationRequest,
        @Header("X-API-Key") apiKey: String
    ): TranslationResponse

    @Multipart
    @Headers("Accept: application/json")
    @POST("v1/indic_visual_query")
    suspend fun visualQuery(
        @Part file: MultipartBody.Part,
        @Part("query") query: RequestBody,
        @Query("src_lang") srcLang: String,
        @Query("tgt_lang") tgtLang: String,
        @Header("X-API-Key") apiKey: String
    ): VisualQueryResponse

    @Multipart
    @POST("v1/speech_to_speech")
    suspend fun speechToSpeech(
        @Query("language") language: String,
        @Part file: MultipartBody.Part,
        @Part("voice") voice: RequestBody,
        @Header("X-API-Key") apiKey: String
    ): Response<ResponseBody>

    @Multipart
    @POST("v1/extract-text")
    suspend fun extractText(
        @Part file: MultipartBody.Part,
        @Query("page_number") pageNumber: Int,
        @Query("language") language: String,
        @Header("X-API-Key") apiKey: String
    ): ExtractTextResponse

    @Multipart
    @POST("v1/document_summary_v0")
    suspend fun summarizeDocument(
        @Part file: MultipartBody.Part,
        @Part("src_lang") srcLang: RequestBody,
        @Part("tgt_lang") tgtLang: RequestBody,
        @Part("prompt") prompt: RequestBody,
        @Header("X-API-Key") apiKey: String
    ): DocumentSummaryResponse
}

object RetrofitClient {
    private const val BASE_URL_DEFAULT = "exmplasd"
    private const val SUMMARY_BASE_URL = "asdasde"
    private const val API_KEY = "your-hardcoded-api-key" // Replace with your actual API key

    // Return plain audio data
    fun encryptAudio(audio: ByteArray): ByteArray {
        return audio // Return the input audio as-is
    }

    // Return plain text
    fun encryptText(text: String): String {
        return text // Return the input text as-is
    }

    private fun getOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            setLevel(HttpLoggingInterceptor.Level.BODY)
        }

        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun apiService(context: Context): ApiService {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val baseUrl = prefs.getString("api_endpoint", BASE_URL_DEFAULT) ?: BASE_URL_DEFAULT
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(getOkHttpClient())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    fun summaryApiService(): ApiService {
        return Retrofit.Builder()
            .baseUrl(SUMMARY_BASE_URL)
            .client(OkHttpClient.Builder()
                .addInterceptor(HttpLoggingInterceptor().apply { setLevel(HttpLoggingInterceptor.Level.BODY) })
                .connectTimeout(600, TimeUnit.SECONDS)
                .readTimeout(600, TimeUnit.SECONDS)
                .writeTimeout(600, TimeUnit.SECONDS)
                .build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    // Return hardcoded API key
    fun getApiKey(): String {
        return API_KEY
    }
}