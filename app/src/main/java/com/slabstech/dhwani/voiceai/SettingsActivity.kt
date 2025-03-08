package com.slabstech.dhwani.voiceai

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private val client =
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()

        override fun onCreatePreferences(
            savedInstanceState: Bundle?,
            rootKey: String?,
        ) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            // Validate Transcription API Endpoint
            findPreference<EditTextPreference>("transcription_api_endpoint")?.apply {
                setOnPreferenceChangeListener { _, newValue ->
                    val isValid = validateUrl(newValue.toString())
                    if (!isValid) {
                        Toast.makeText(context, "Invalid Transcription API URL", Toast.LENGTH_SHORT).show()
                    }
                    isValid
                }
            }

            // Validate Chat API Endpoint
            findPreference<EditTextPreference>("chat_api_endpoint")?.apply {
                setOnPreferenceChangeListener { _, newValue ->
                    val isValid = validateUrl(newValue.toString())
                    if (!isValid) {
                        Toast.makeText(context, "Invalid Chat API URL", Toast.LENGTH_SHORT).show()
                    }
                    isValid
                }
            }

            // Validate Chat API Key (optional - ensure not empty)
            findPreference<EditTextPreference>("chat_api_key")?.apply {
                setOnPreferenceChangeListener { _, newValue ->
                    val isValid = newValue.toString().isNotEmpty()
                    if (!isValid) {
                        Toast.makeText(context, "Chat API Key cannot be empty", Toast.LENGTH_SHORT).show()
                    }
                    isValid
                }
            }

            // Test Endpoints Button
            findPreference<Preference>("test_endpoints")?.apply {
                setOnPreferenceClickListener {
                    testEndpoints()
                    true
                }
            }
        }

        private fun validateUrl(url: String): Boolean {
            val trimmedUrl = url.trim()
            if (trimmedUrl.isEmpty()) return false
            if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) return false
            val hostPart = trimmedUrl.substringAfter("://").substringBefore("/")
            if (hostPart.isEmpty()) return false
            val validHostPattern = Regex("^[a-zA-Z0-9.-]+(:[0-9]+)?$")
            return validHostPattern.matches(hostPart)
        }

        private fun testEndpoints() {
            val prefs = preferenceManager.sharedPreferences
            val transcriptionUrl =
                prefs?.getString("transcription_api_endpoint", "https://gaganyatri-asr-indic-server-cpu.hf.space/transcribe/") ?: ""
            val chatUrl =
                prefs?.getString("chat_api_endpoint", "https://gaganyatri-llm-indic-server-cpu.hf.space/chat") ?: ""
            val language =
                prefs?.getString("language", "kannada") ?: "kannada"

            CoroutineScope(Dispatchers.Main).launch {
                val transcriptionResult = testEndpoint("$transcriptionUrl?language=$language")
                val chatResult = testEndpoint(chatUrl)

                val message =
                    buildString {
                        append("Transcription API: ")
                        append(if (transcriptionResult) "Success" else "Failed")
                        append("\nChat API: ")
                        append(if (chatResult) "Success" else "Failed")
                    }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }

        private suspend fun testEndpoint(url: String): Boolean =
            withContext(Dispatchers.IO) {
                try {
                    val request =
                        Request.Builder()
                            .url(url)
                            .head()
                            .build()
                    val response = client.newCall(request).execute()
                    response.isSuccessful
                } catch (e: IOException) {
                    e.printStackTrace()
                    false
                }
            }
    }
}
