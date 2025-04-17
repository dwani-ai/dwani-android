package com.slabstech.dhwani.voiceai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.media.AudioRecord
import android.media.MediaPlayer
import android.text.Editable
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import com.slabstech.dhwani.voiceai.utils.SpeechUtils

class AnswerActivity : MessageActivity() {

    private val RECORD_AUDIO_PERMISSION_CODE = 100
    private var audioRecord: AudioRecord? = null
    private var audioFile: File? = null
    private lateinit var audioLevelBar: ProgressBar
    private lateinit var progressBar: ProgressBar
    private lateinit var pushToTalkFab: FloatingActionButton
    private lateinit var textQueryInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var toolbar: Toolbar
    private lateinit var ttsProgressBar: ProgressBar
    private var isRecording = false
    private var mediaPlayer: MediaPlayer? = null
    private val AUTO_PLAY_KEY = "auto_play_tts"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("AnswerActivity", "onCreate called")
        setContentView(R.layout.activity_answer)

        historyRecyclerView = findViewById(R.id.historyRecyclerView)
        audioLevelBar = findViewById(R.id.audioLevelBar)
        progressBar = findViewById(R.id.progressBar)
        pushToTalkFab = findViewById(R.id.pushToTalkFab)
        textQueryInput = findViewById(R.id.textQueryInput)
        sendButton = findViewById(R.id.sendButton)
        toolbar = findViewById(R.id.toolbar)
        ttsProgressBar = findViewById(R.id.ttsProgressBar)

        setSupportActionBar(toolbar)
        setupMessageList()
        setupBottomNavigation(R.id.nav_answer)

        if (!prefs.contains(AUTO_PLAY_KEY)) {
            prefs.edit().putBoolean(AUTO_PLAY_KEY, true).apply()
        }
        if (!prefs.contains("tts_enabled")) {
            prefs.edit().putBoolean("tts_enabled", false).apply()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                RECORD_AUDIO_PERMISSION_CODE
            )
        }

        pushToTalkFab.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRecording()
                    animateFabRecordingStart()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    stopRecording()
                    animateFabRecordingStop()
                    true
                }
                else -> false
            }
        }

        sendButton.setOnClickListener {
            val query = textQueryInput.text.toString().trim()
            if (query.isNotEmpty()) {
                val timestamp = DateUtils.getCurrentTimestamp()
                val message = Message("Query: $query", timestamp, true)
                messageList.add(message)
                messageAdapter.notifyItemInserted(messageList.size - 1)
                scrollToLatestMessage()
                getChatResponse(query)
                textQueryInput.text.clear()
            } else {
                Toast.makeText(this, "Please enter a query", Toast.LENGTH_SHORT).show()
            }
        }

        textQueryInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (s.isNullOrEmpty()) {
                    sendButton.visibility = View.GONE
                    pushToTalkFab.visibility = View.VISIBLE
                } else {
                    sendButton.visibility = View.VISIBLE
                    pushToTalkFab.visibility = View.GONE
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        Log.d("AnswerActivity", "onResume called")
    }

    private fun startRecording() {
        AudioUtils.startPushToTalkRecording(this, audioLevelBar, { animateFabRecordingStart() }) { file ->
            file?.let { sendAudioToApi(it) }
        }
    }

    private fun stopRecording() {
        AudioUtils.stopRecording(audioRecord, isRecording)
        animateFabRecordingStop()
    }

    private fun sendAudioToApi(audioFile: File) {
        val token = AuthManager.getToken(this) ?: return
        val selectedLanguage = prefs.getString("language", "kannada") ?: "kannada"
        val supportedLanguage = when (selectedLanguage.lowercase()) {
            "hindi" -> "hindi"
            "tamil" -> "tamil"
            else -> "kannada"
        }

        val audioBytes = audioFile.readBytes()
        val encryptedAudio = RetrofitClient.encryptAudio(audioBytes, sessionKey)
        val encryptedFile = File(cacheDir, "encrypted_${audioFile.name}")
        FileOutputStream(encryptedFile).use { it.write(encryptedAudio) }

        val encryptedLanguage = RetrofitClient.encryptText(supportedLanguage, sessionKey)
        val requestFile = encryptedFile.asRequestBody("application/octet-stream".toMediaType())
        val filePart = MultipartBody.Part.createFormData("file", encryptedFile.name, requestFile)

        lifecycleScope.launch {
            ApiUtils.performApiCall(
                context = this@AnswerActivity,
                progressBar = progressBar,
                apiCall = {
                    val cleanSessionKey = Base64.encodeToString(sessionKey, Base64.NO_WRAP)
                    RetrofitClient.apiService(this@AnswerActivity).transcribeAudio(
                        filePart,
                        encryptedLanguage,
                        "Bearer $token",
                        cleanSessionKey
                    )
                },
                onSuccess = { response ->
                    val voiceQueryText = response.text
                    val timestamp = DateUtils.getCurrentTimestamp()
                    if (voiceQueryText.isNotEmpty()) {
                        val message = Message("Voice Query: $voiceQueryText", timestamp, true)
                        messageList.add(message)
                        messageAdapter.notifyItemInserted(messageList.size - 1)
                        scrollToLatestMessage()
                        getChatResponse(voiceQueryText)
                    } else {
                        Toast.makeText(this@AnswerActivity, "Voice query empty", Toast.LENGTH_SHORT).show()
                    }
                },
                onError = { e -> Log.e("AnswerActivity", "Transcription failed: ${e.message}", e) }
            )
        }
    }

    private fun getChatResponse(prompt: String) {
        val token = AuthManager.getToken(this) ?: return
        val selectedLanguage = prefs.getString("language", "kannada") ?: "kannada"
        val languageMap = mapOf(
            "english" to "eng_Latn",
            "hindi" to "hin_Deva",
            "kannada" to "kan_Knda",
            "tamil" to "tam_Taml",
            "malayalam" to "mal_Mlym",
            "telugu" to "tel_Telu",
            "german" to "deu_Latn",
            "french" to "fra_Latn",
            "dutch" to "nld_Latn",
            "spanish" to "spa_Latn",
            "italian" to "ita_Latn",
            "portuguese" to "por_Latn",
            "russian" to "rus_Cyrl",
            "polish" to "pol_Latn"
        )
        val srcLang = languageMap[selectedLanguage] ?: "kan_Knda"
        val tgtLang = srcLang

        val encryptedPrompt = RetrofitClient.encryptText(prompt, sessionKey)
        val encryptedSrcLang = RetrofitClient.encryptText(srcLang, sessionKey)
        val encryptedTgtLang = RetrofitClient.encryptText(tgtLang, sessionKey)

        val chatRequest = ChatRequest(encryptedPrompt, encryptedSrcLang, encryptedTgtLang)

        lifecycleScope.launch {
            ApiUtils.performApiCall(
                context = this@AnswerActivity,
                progressBar = progressBar,
                apiCall = {
                    val cleanSessionKey = Base64.encodeToString(sessionKey, Base64.NO_WRAP)
                    RetrofitClient.apiService(this@AnswerActivity).chat(
                        chatRequest,
                        "Bearer $token",
                        cleanSessionKey
                    )
                },
                onSuccess = { response ->
                    val answerText = response.response
                    val timestamp = DateUtils.getCurrentTimestamp()
                    val message = Message("Answer: $answerText", timestamp, false)
                    messageList.add(message)
                    messageAdapter.notifyItemInserted(messageList.size - 1)
                    scrollToLatestMessage()

                    val encryptedAnswerText = RetrofitClient.encryptText(answerText, sessionKey)
                    SpeechUtils.textToSpeech(
                        context = this@AnswerActivity,
                        scope = lifecycleScope,
                        text = encryptedAnswerText,
                        message = message,
                        recyclerView = historyRecyclerView,
                        adapter = messageAdapter,
                        ttsProgressBarVisibility = { visible -> ttsProgressBar.visibility = if (visible) View.VISIBLE else View.GONE },
                        srcLang = tgtLang,
                        sessionKey = sessionKey
                    )
                },
                onError = { e -> Log.e("AnswerActivity", "Chat failed: ${e.message}", e) }
            )
        }
    }

    override fun toggleAudioPlayback(message: Message, button: ImageButton) {
        mediaPlayer = SpeechUtils.toggleAudioPlayback(
            context = this,
            message = message,
            button = button,
            recyclerView = historyRecyclerView,
            adapter = messageAdapter,
            mediaPlayer = mediaPlayer,
            playIconResId = android.R.drawable.ic_media_play,
            stopIconResId = R.drawable.ic_media_stop
        )
    }

    private fun animateFabRecordingStart() {
        pushToTalkFab.setImageResource(android.R.drawable.ic_media_pause)
        val scaleUp = ObjectAnimator.ofPropertyValuesHolder(
            pushToTalkFab,
            PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.2f),
            PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.2f)
        )
        scaleUp.duration = 200
        scaleUp.start()
        pushToTalkFab.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_red_light)
    }

    private fun animateFabRecordingStop() {
        pushToTalkFab.setImageResource(R.drawable.ic_mic)
        val scaleDown = ObjectAnimator.ofPropertyValuesHolder(
            pushToTalkFab,
            PropertyValuesHolder.ofFloat("scaleX", 1.2f, 1.0f),
            PropertyValuesHolder.ofFloat("scaleY", 1.2f, 1.0f)
        )
        scaleDown.duration = 200
        scaleDown.start()
        pushToTalkFab.backgroundTintList = ContextCompat.getColorStateList(this, R.color.whatsapp_green)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("AnswerActivity", "onDestroy called")
        mediaPlayer?.release()
        mediaPlayer = null
        messageList.forEach { it.audioFile?.delete() }
        audioRecord?.release()
        audioRecord = null
        audioFile?.delete()
    }
}