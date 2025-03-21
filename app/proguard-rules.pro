# Keep Retrofit interfaces
-keep class retrofit2.* { *; }

# Keep model classes used in API responses
-keep class com.slabstech.dhwani.voiceai.models.** { *; }

# Keep Gson/Moshi annotations
-keepattributes Signature
-keepattributes *Annotation*

# Prevent obfuscation of classes used by Retrofit and Gson
-keep class com.google.gson.* { *; }
-keep class retrofit2.converter.gson.* { *; }

# Keep OkHttp classes
-keep class okhttp3.* { *; }
-keep class okio.* { *; }

# Prevent stripping of lifecycle-related classes
-keep class androidx.lifecycle.** { *; }
