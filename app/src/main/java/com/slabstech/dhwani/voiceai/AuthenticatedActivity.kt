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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        retrieveSessionKey()
        checkAuthentication()
    }

    override fun onResume() {
        super.onResume()
        val isDarkTheme = prefs.getBoolean("dark_theme", false)
        if (currentTheme != isDarkTheme) {
            currentTheme = isDarkTheme
            recreate()
            return
        }

        sessionDialog = AlertDialog.Builder(this)
            .setMessage("Checking session...")
            .setCancelable(false)
            .create()
        sessionDialog?.show()

        lifecycleScope.launch {
            val tokenValid = AuthManager.refreshTokenIfNeeded(this@AuthenticatedActivity)
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
            Log.e("AuthenticatedActivity", "Session key missing")
            Toast.makeText(this, "Session error. Please log in again.", Toast.LENGTH_LONG).show()
            AuthManager.logout(this)
            ByteArray(0)
        }
    }

    private fun checkAuthentication() {
        lifecycleScope.launch {
            if (!AuthManager.isAuthenticated(this@AuthenticatedActivity) || !AuthManager.refreshTokenIfNeeded(this@AuthenticatedActivity)) {
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