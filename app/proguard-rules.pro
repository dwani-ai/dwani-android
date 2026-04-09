# --- Gson + Kotlin API models (R8 renames JVM fields; Gson needs stable JSON names) ---
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod

-keep class com.slabstech.dhwani.voiceai.LoginRequest { *; }
-keep class com.slabstech.dhwani.voiceai.RegisterRequest { *; }
-keep class com.slabstech.dhwani.voiceai.TokenResponse { *; }
-keep class com.slabstech.dhwani.voiceai.TranscriptionRequest { *; }
-keep class com.slabstech.dhwani.voiceai.TranscriptionResponse { *; }
-keep class com.slabstech.dhwani.voiceai.ChatRequest { *; }
-keep class com.slabstech.dhwani.voiceai.ChatResponse { *; }
-keep class com.slabstech.dhwani.voiceai.TranslationRequest { *; }
-keep class com.slabstech.dhwani.voiceai.TranslationResponse { *; }
-keep class com.slabstech.dhwani.voiceai.VisualQueryRequest { *; }
-keep class com.slabstech.dhwani.voiceai.VisualQueryResponse { *; }
-keep class com.slabstech.dhwani.voiceai.ExtractTextResponse { *; }
-keep class com.slabstech.dhwani.voiceai.DocumentSummaryResponse { *; }
-keep class com.slabstech.dhwani.voiceai.Page { *; }
-keep class com.slabstech.dhwani.voiceai.PdfSummaryResponse { *; }

# Retrofit / OkHttp ship consumer rules; avoid blanket -keep on okhttp3/retrofit2 (hurts shrinking).

# --- AndroidX / lifecycle (minimal) ---
-keep class androidx.lifecycle.** { *; }
