package com.slabstech.dhwani.voiceai

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
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
        // Enable edge-to-edge before setting content view
        WindowCompat.setDecorFitsSystemWindows(window, false)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Apply window insets to root view
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settings_container)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                top = systemBars.top,
                bottom = systemBars.bottom
            )
            insets
        }

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
        private val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            // Text-to-Speech toggle summary
            findPreference<SwitchPreferenceCompat>("tts_enabled")?.apply {
                setOnPreferenceChangeListener { preference, newValue ->
                    preference.summary = if (newValue as Boolean) {
                        "Text-to-speech is enabled"
                    } else {
                        "Text-to-speech is disabled"
                    }
                    true
                }
                summary = if (isChecked) {
                    "Text-to-speech is enabled"
                } else {
                    "Text-to-speech is disabled"
                }
            }

            // Auto-Play for TTS summary
            findPreference<SwitchPreferenceCompat>("auto_play_tts")?.apply {
                setOnPreferenceChangeListener { preference, newValue ->
                    preference.summary = if (newValue as Boolean) {
                        "TTS audio plays automatically"
                    } else {
                        "TTS audio requires manual play"
                    }
                    true
                }
                summary = if (isChecked) {
                    "TTS audio plays automatically"
                } else {
                    "TTS audio requires manual play"
                }
            }
        }
    }
}
