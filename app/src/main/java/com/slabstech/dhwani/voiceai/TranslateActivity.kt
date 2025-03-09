package com.slabstech.dhwani.voiceai

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.TextView

class TranslateActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_translate)
        findViewById<TextView>(R.id.translate_text).text = "Translate Screen"
    }
}