package com.slabstech.dhwani.voiceai

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.TextView

class AnswerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_answer)
        findViewById<TextView>(R.id.answer_text).text = "Answer Screen"
    }
}