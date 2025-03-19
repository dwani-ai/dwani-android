package com.slabstech.dhwani.voiceai

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.*

// Data classes
data class LoginRequest(val username: String, val password: String)
data class TokenResponse(val access_token: String, val token_type: String)
data class TranscriptionRequest(val language: String)
data class TranscriptionResponse(val text: String)
data class ChatRequest(val prompt: String, val src_lang: String, val tgt_lang: String)
data class ChatResponse(val response: String)
data class TranslationRequest(val sentences: List<String>, val src_lang: String, val tgt_lang: String)
data class TranslationResponse(val translations: List<String>)
data class VisualQueryResponse(val answer: String)

// API Service Interface
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

    @POST("v1/refresh")
    suspend fun refreshToken(@Header("Authorization") token: String): TokenResponse
}