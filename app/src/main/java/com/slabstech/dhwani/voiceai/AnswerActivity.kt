package com.slabstech.dhwani.voiceai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.media.AudioRecord
import android.media.MediaPlayer
import android.text.Editable
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.*
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import com.slabstech.dhwani.voiceai.utils.SpeechUtils

class AnswerActivity : MessageActivity() {

    private val RECORD_AUDIO_PERMISSION_CODE = 100
    private var audioRecord: AudioRecord? = null
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

        // Enable edge-to-edge layout
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContentView(R.layout.activity_answer)

        // Handle Insets (gesture nav & cutouts)
        setupInsets()

        // View initializations
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

        // Preferences initialization
        if (!prefs.contains(AUTO_PLAY_KEY)) {
            prefs.edit().putBoolean(AUTO_PLAY_KEY, true).apply()
        }
        if (!prefs.contains("tts_enabled")) {
            prefs.edit().putBoolean("tts_enabled", false).apply()
        }

        // Audio permission check
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_PERMISSION_CODE)
        }

        // Push to Talk Record Toggle
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

        // Handle Send button click
        sendButton.setOnClickListener {
            val query = textQueryInput.text.toString().trim()
            if (query.isNotEmpty()) {
                submitQuery(query)
            } else {
                Toast.makeText(this, "Please enter a query", Toast.LENGTH_SHORT).show()
            }
        }

        // Handle "Send" action from keyboard
        textQueryInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                val query = textQueryInput.text.toString().trim()
                if (query.isNotEmpty()) {
                    submitQuery(query)
                    true
                } else {
                    Toast.makeText(this, "Please enter a query", Toast.LENGTH_SHORT).show()
                    false
                }
            } else {
                false
            }
        }

        // Scroll RecyclerView and show keyboard when EditText gains focus
        textQueryInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                scrollToLatestMessage()
                showKeyboard()
            }
        }

        // Toggle between TTS and Send button
        textQueryInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (s.isNullOrEmpty()) {
                    sendButton.visibility = View.GONE
                    pushToTalkFab.visibility = View.VISIBLE
                } else {
                    sendButton.visibility = View.VISIBLE
                    pushToTalkFab.visibility = View.GONE
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun showKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(textQueryInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun setupInsets() {
        val rootView = findViewById<View>(R.id.coordinatorLayout)
        val bottomNav = findViewById<View>(R.id.bottomNavigation)
        val bottomBar = findViewById<View>(R.id.bottomBar)

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, 0) // Top padding for status bar
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(bottomBar) { view, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = imeInsets.bottom + systemBars.bottom + 4) // Adjust for keyboard and nav bar
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(bottomNav) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = systemBars.bottom) // Only nav bar padding for bottom navigation
            insets
        }
    }
    private fun submitQuery(query: String) {
        val timestamp = DateUtils.getCurrentTimestamp()
        val message = Message("Query: $query", timestamp, true, null, null)
        messageList.add(message)
        messageAdapter.notifyItemInserted(messageList.size - 1)
        scrollToLatestMessage()
        getChatResponse(query)
        textQueryInput.text.clear()
        // Hide keyboard after sending
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(textQueryInput.windowToken, 0)
    }

    override fun onResume() {
        super.onResume()
        Log.d("AnswerActivity", "onResume called")
    }

    private fun startRecording() {
        AudioUtils.startPushToTalkRecording(this, audioLevelBar, { animateFabRecordingStart() }) { file ->
            file?.let {
                val uri = Uri.fromFile(it)
                sendAudioToApi(it, uri)
            }
        }
    }

    private fun stopRecording() {
        AudioUtils.stopRecording(audioRecord, isRecording)
        animateFabRecordingStop()
    }

    private fun sendAudioToApi(audioFile: File, audioUri: Uri) {
        val selectedLanguage = prefs.getString("language", "kannada") ?: "kannada"
        val language = when (selectedLanguage.lowercase()) {
            "hindi", "tamil", "english", "german", "telugu" -> selectedLanguage.lowercase()
            else -> "kannada"
        }

        val requestFile = audioFile.asRequestBody("audio/mpeg".toMediaType())
        val filePart = MultipartBody.Part.createFormData("file", audioFile.name, requestFile)

        lifecycleScope.launch {
            ApiUtils.performApiCall(
                context = this@AnswerActivity,
                progressBar = progressBar,
                apiCall = {
                    RetrofitClient.apiService(this@AnswerActivity).transcribeAudio(
                        filePart,
                        language,
                        RetrofitClient.getApiKey()
                    )
                },
                onSuccess = { response ->
                    val voiceQueryText = response.text
                    val timestamp = DateUtils.getCurrentTimestamp()

                    if (voiceQueryText.isNotEmpty()) {
                        val message = Message("Voice Query: $voiceQueryText", timestamp, true, audioUri, "audio")
                        messageList.add(message)
                        messageAdapter.notifyItemInserted(messageList.size - 1)
                        scrollToLatestMessage()
                        getChatResponse(voiceQueryText)
                    } else {
                        Toast.makeText(this@AnswerActivity, "Voice query empty", Toast.LENGTH_SHORT).show()
                    }

                    audioFile.delete()
                },
                onError = { e ->
                    Log.e("AnswerActivity", "Transcription failed: ${e.message}", e)
                    audioFile.delete()
                }
            )
        }
    }

    private fun getChatResponse(prompt: String) {
        val selectedLanguage = prefs.getString("language", "kannada") ?: "kannada"
        val languageMap = mapOf(
            "english" to "eng_Latn",
            "hindi" to "hin_Deva",
            "kannada" to "kan_Knda",
            "tamil" to "tam_Taml",
            "german" to "deu_Latn",
            "telugu" to "tel_Telu"
        )
        val langCode = languageMap[selectedLanguage] ?: "kan_Knda"

        val request = ChatRequest(prompt, langCode, langCode)

        lifecycleScope.launch {
            ApiUtils.performApiCall(
                context = this@AnswerActivity,
                progressBar = progressBar,
                apiCall = {
                    RetrofitClient.apiService(this@AnswerActivity).chat(
                        request,
                        RetrofitClient.getApiKey()
                    )
                },
                onSuccess = { response ->
                    val answerText = response.response
                    val timestamp = DateUtils.getCurrentTimestamp()
                    val message = Message("Answer: $answerText", timestamp, false, null, null)
                    messageList.add(message)
                    messageAdapter.notifyItemInserted(messageList.size - 1)
                    scrollToLatestMessage()
                    SpeechUtils.textToSpeech(
                        context = this@AnswerActivity,
                        scope = lifecycleScope,
                        text = answerText,
                        message = message,
                        recyclerView = historyRecyclerView,
                        adapter = messageAdapter,
                        ttsProgressBarVisibility = { visible ->
                            ttsProgressBar.visibility = if (visible) View.VISIBLE else View.GONE
                        },
                        srcLang = langCode
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
        ).apply {
            duration = 200
            start()
        }
        pushToTalkFab.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_red_light)
    }

    private fun animateFabRecordingStop() {
        pushToTalkFab.setImageResource(R.drawable.ic_mic)
        val scaleDown = ObjectAnimator.ofPropertyValuesHolder(
            pushToTalkFab,
            PropertyValuesHolder.ofFloat("scaleX", 1.2f, 1.0f),
            PropertyValuesHolder.ofFloat("scaleY", 1.2f, 1.0f)
        ).apply {
            duration = 200
            start()
        }
        pushToTalkFab.backgroundTintList = ContextCompat.getColorStateList(this, R.color.whatsapp_green)
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
        audioRecord?.release()
        audioRecord = null
    }
}