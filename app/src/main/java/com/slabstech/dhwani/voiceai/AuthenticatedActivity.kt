package com.slabstech.dhwani.voiceai

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.preference.PreferenceManager

abstract class AuthenticatedActivity : AppCompatActivity() {
    protected val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("AuthenticatedActivity", "onCreate: Activity created")
    }

    override fun onResume() {
        super.onResume()
        Log.d("AuthenticatedActivity", "onResume: Activity resumed")
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
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * For layouts with [R.id.coordinatorLayout], [R.id.bottomBar], [R.id.bottomNavigation]
     * (Answer, Translate, Voice assistant): keeps content out of status bar, gesture nav, and IME.
     */
    protected fun setupAnswerStyleWindowInsets() {
        val rootView = findViewById<View>(R.id.coordinatorLayout) ?: return
        val bottomBar = findViewById<View>(R.id.bottomBar)
        val bottomNav = findViewById<View>(R.id.bottomNavigation)

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, 0)
            insets
        }

        bottomBar?.let { bar ->
            ViewCompat.setOnApplyWindowInsetsListener(bar) { view, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
                view.updatePadding(bottom = imeInsets.bottom + systemBars.bottom + 8)
                insets
            }
        }

        bottomNav?.let { nav ->
            ViewCompat.setOnApplyWindowInsetsListener(nav) { view, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.updatePadding(bottom = systemBars.bottom)
                insets
            }
        }
    }
}