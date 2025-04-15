package com.slabstech.dhwani.voiceai

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.launch

abstract class AuthenticatedActivity : AppCompatActivity() {
    protected var sessionDialog: AlertDialog? = null
    protected lateinit var sessionKey: ByteArray
    protected val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    protected var currentTheme: Boolean? = null
    private var isRefreshingToken = false // Prevent concurrent refreshes

    override fun onCreate(savedInstanceState: Bundle?) {
        currentTheme = prefs.getBoolean("dark_theme", false)
        super.onCreate(savedInstanceState)
        Log.d("_authenticatedActivity", "onCreate: Activity created with theme $currentTheme")
        retrieveSessionKey()
        checkAuthentication()
    }

    override fun onResume() {
        super.onResume()
        Log.d("AuthenticatedActivity", "onResume: Checking session")

        if (isRefreshingToken) {
            Log.d("AuthenticatedActivity", "onResume: Skipping token refresh, already in progress")
            return
        }

        sessionDialog = AlertDialog.Builder(this)
            .setMessage("Checking session...")
            .setCancelable(false)
            .create()
        sessionDialog?.show()

        lifecycleScope.launch {
            isRefreshingToken = true
            try {
                val tokenValid = AuthManager.refreshTokenIfNeeded(this@AuthenticatedActivity)
                Log.d("AuthenticatedActivity", "onResume: Token valid: $tokenValid")
                if (tokenValid) {
                    sessionDialog?.dismiss()
                    sessionDialog = null
                } else {
                    sessionDialog?.dismiss()
                    sessionDialog = null
                    AlertDialog.Builder(this@AuthenticatedActivity)
                        .setTitle("Session Expired")
                        .setMessage("Your session could not be refreshed. Please log in again.")
                        .setPositiveButton("OK") { _, _ ->
                            AuthManager.logout(this@AuthenticatedActivity)
                        }
                        .setCancelable(false)
                        .show()
                }
            } finally {
                isRefreshingToken = false
            }
        }
    }

    private fun retrieveSessionKey() {
        sessionKey = prefs.getString("session_key", null)?.let { encodedKey ->
            try {
                val cleanKey = encodedKey.trim()
                if (!StringUtils.isValidBase64(cleanKey)) {
                    throw IllegalArgumentException("Invalid Base64 format for session key")
                }
                Base64.decode(cleanKey, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                Log.e("AuthenticatedActivity", "Invalid session key format: ${e.message}")
                null
            }
        } ?: run {
            Log.e("AuthenticatedActivity", "Session key missing, generating new one")
            val newKey = ByteArray(16).apply { java.security.SecureRandom().nextBytes(this) }
            prefs.edit().putString("session_key", Base64.encodeToString(newKey, Base64.NO_WRAP)).apply()
            newKey
        }
    }

    private fun checkAuthentication() {
        lifecycleScope.launch {
            if (!AuthManager.isAuthenticated(this@AuthenticatedActivity)) {
                Log.d("AuthenticatedActivity", "checkAuthentication: Not authenticated, logging out")
                AuthManager.logout(this@AuthenticatedActivity)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_logout -> {
                AuthManager.logout(this)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}