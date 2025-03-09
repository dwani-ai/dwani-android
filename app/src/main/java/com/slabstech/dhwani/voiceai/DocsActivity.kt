package com.slabstech.dhwani.voiceai

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.TextView

class DocsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_docs)
        findViewById<TextView>(R.id.docs_text).text = "Docs Screen"
    }
}