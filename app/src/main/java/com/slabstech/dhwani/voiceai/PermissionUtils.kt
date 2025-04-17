package com.slabstech.dhwani.voiceai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object PermissionUtils {
    fun checkAndRequestPermission(context: Context, permission: String, requestCode: Int): Boolean {
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                context as AppCompatActivity,
                arrayOf(permission),
                requestCode
            )
            return false
        }
        return true
    }
}