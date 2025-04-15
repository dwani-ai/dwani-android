package com.slabstech.dhwani.voiceai

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomnavigation.BottomNavigationView

object NavigationUtils {
    fun setupBottomNavigation(context: Context, bottomNavigation: BottomNavigationView, currentItemId: Int) {
        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_answer -> {
                    if (currentItemId != R.id.nav_answer) {
                        AlertDialog.Builder(context)
                            .setMessage("Switch to Answer?")
                            .setPositiveButton("Yes") { _, _ ->
                                context.startActivity(Intent(context, AnswerActivity::class.java))
                            }
                            .setNegativeButton("No", null)
                            .show()
                        false
                    } else true
                }
                R.id.nav_translate -> {
                    if (currentItemId != R.id.nav_translate) {
                        AlertDialog.Builder(context)
                            .setMessage("Switch to Translate?")
                            .setPositiveButton("Yes") { _, _ ->
                                context.startActivity(Intent(context, TranslateActivity::class.java))
                            }
                            .setNegativeButton("No", null)
                            .show()
                        false
                    } else true
                }
                R.id.nav_docs -> {
                    if (currentItemId != R.id.nav_docs) {
                        AlertDialog.Builder(context)
                            .setMessage("Switch to Docs?")
                            .setPositiveButton("Yes") { _, _ ->
                                context.startActivity(Intent(context, DocsActivity::class.java))
                            }
                            .setNegativeButton("No", null)
                            .show()
                        false
                    } else true
                }
                R.id.nav_voice -> {
                    if (currentItemId != R.id.nav_voice) {
                        AlertDialog.Builder(context)
                            .setMessage("Switch to Voice?")
                            .setPositiveButton("Yes") { _, _ ->
                                context.startActivity(Intent(context, VoiceDetectionActivity::class.java))
                            }
                            .setNegativeButton("No", null)
                            .show()
                        false
                    } else true
                }
                else -> false
            }
        }
        bottomNavigation.selectedItemId = currentItemId
    }
}