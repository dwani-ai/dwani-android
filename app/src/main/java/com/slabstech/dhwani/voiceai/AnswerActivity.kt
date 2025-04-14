package com.slabstech.dhwani.voiceai

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder.AudioSource
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.appcompat.widget.Toolbar
import android.view.Menu
import android.view.MenuItem
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.text.Editable
import com.slabstech.dhwani.voiceai.utils.SpeechUtils
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*

class AnswerActivity : AppCompatActivity() {

    private val RECORD_AUDIO_PERMISSION_CODE = 100
    private var audioRecord: AudioRecord? = null
    private var audioFile: File? = null
    private lateinit var historyRecyclerView: RecyclerView
    private lateinit var audioLevelBar: ProgressBar
    private lateinit var progressBar: ProgressBar
    private lateinit var pushToTalkFab: FloatingActionButton
    private lateinit var textQueryInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var toolbar: Toolbar
    private lateinit var ttsProgressBar: ProgressBar
    private var isRecording = false
    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val MIN_RECORDING_DURATION_MS = 1000L
    private var recordingStartTime: Long = 0L
    private val messageList = mutableListOf<Message>()
    private lateinit var messageAdapter: MessageAdapter
    private var lastQuery: String? = null
    private var currentTheme: Boolean? = null
    private var mediaPlayer: MediaPlayer? = null
    private val AUTO_PLAY_KEY = "auto_play_tts"
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private var sessionDialog: AlertDialog? = null
    private lateinit var sessionKey: ByteArray
    private val GCM_TAG_LENGTH = 16
    private val GCM_NONCE_LENGTH = 12

    override fun onCreate(savedInstanceState: Bundle?) {
        val isDarkTheme = prefs.getBoolean("dark_theme", false)
        setTheme(if (isDarkTheme) R.style.Theme_DhwaniVoiceAI_Dark else R.style.Theme_DhwaniVoiceAI_Light)
        currentTheme = isDarkTheme

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_answer)

        // Retrieve session key with validation
        sessionKey = prefs.getString("session_key", null)?.let { encodedKey ->
            try {
                val cleanKey = encodedKey.trim()
                if (!isValidBase64(cleanKey)) {
                    throw IllegalArgumentException("Invalid Base64 format for session key")
                }
                Base64.decode(cleanKey, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                Log.e("AnswerActivity", "Invalid session key format: ${e.message}")
                null
            }
        } ?: run {
            Log.e("AnswerActivity", "Session key missing")
            Toast.makeText(this, "Session error. Please log in again.", Toast.LENGTH_LONG).show()
            AuthManager.logout(this)
            ByteArray(0)
        }

        checkAuthentication()

        try {
            historyRecyclerView = findViewById(R.id.historyRecyclerView)
            audioLevelBar = findViewById(R.id.audioLevelBar)
            progressBar = findViewById(R.id.progressBar)
            pushToTalkFab = findViewById(R.id.pushToTalkFab)
            textQueryInput = findViewById(R.id.textQueryInput)
            sendButton = findViewById(R.id.sendButton)
            toolbar = findViewById(R.id.toolbar)
            ttsProgressBar = findViewById(R.id.ttsProgressBar)
            val bottomNavigation = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNavigation)

            setSupportActionBar(toolbar)

            if (!prefs.contains(AUTO_PLAY_KEY)) {
                prefs.edit().putBoolean(AUTO_PLAY_KEY, true).apply()
            }
            if (!prefs.contains("tts_enabled")) {
                prefs.edit().putBoolean("tts_enabled", false).apply()
            }

            messageAdapter = MessageAdapter(messageList, { position ->
                showMessageOptionsDialog(position)
            }, { message, button ->
                toggleAudioPlayback(message, button)
            })
            historyRecyclerView.apply {
                layoutManager = LinearLayoutManager(this@AnswerActivity)
                adapter = messageAdapter
                visibility = View.VISIBLE
                setBackgroundColor(ContextCompat.getColor(this@AnswerActivity, android.R.color.transparent))
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
                    val timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    val message = Message("Query: $query", timestamp, true)
                    messageList.add(message)
                    messageAdapter.notifyItemInserted(messageList.size - 1)
                    historyRecyclerView.requestLayout()
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

            bottomNavigation.setOnItemSelectedListener { item ->
                when (item.itemId) {
                    R.id.nav_answer -> true
                    R.id.nav_translate -> {
                        AlertDialog.Builder(this)
                            .setMessage("Switch to Translate?")
                            .setPositiveButton("Yes") { _, _ ->
                                startActivity(Intent(this, TranslateActivity::class.java))
                            }
                            .setNegativeButton("No", null)
                            .show()
                        false
                    }
                    R.id.nav_docs -> {
                        AlertDialog.Builder(this)
                            .setMessage("Switch to Docs?")
                            .setPositiveButton("Yes") { _, _ ->
                                startActivity(Intent(this, DocsActivity::class.java))
                            }
                            .setNegativeButton("No", null)
                            .show()
                        false
                    }
                    R.id.nav_voice -> {
                        AlertDialog.Builder(this)
                            .setMessage("Switch to Voice?")
                            .setPositiveButton("Yes") { _, _ ->
                                startActivity(Intent(this, VoiceDetectionActivity::class.java))
                            }
                            .setNegativeButton("No", null)
                            .show()
                        false
                    }
                    else -> false
                }
            }
            bottomNavigation.selectedItemId = R.id.nav_answer
        } catch (e: Exception) {
            Log.e("AnswerActivity", "Crash in onCreate: ${e.message}", e)
            Toast.makeText(this, "Initialization failed: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun checkAuthentication() {
        lifecycleScope.launch {
            if (!AuthManager.isAuthenticated(this@AnswerActivity) || !AuthManager.refreshTokenIfNeeded(this@AnswerActivity)) {
                Log.d("AnswerActivity", "Authentication failed, logging out")
                AuthManager.logout(this@AnswerActivity)
            }
        }
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
            val tokenValid = AuthManager.refreshTokenIfNeeded(this@AnswerActivity)
            Log.d("AnswerActivity", "onResume: Token valid = $tokenValid")
            if (tokenValid) {
                sessionDialog?.dismiss()
                sessionDialog = null
            } else {
                sessionDialog?.dismiss()
                sessionDialog = null
                AlertDialog.Builder(this@AnswerActivity)
                    .setTitle("Session Expired")
                    .setMessage("Your session could not be refreshed. Please log in again.")
                    .setPositiveButton("OK") { _, _ ->
                        AuthManager.logout(this@AnswerActivity)
                    }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        menu.findItem(R.id.action_auto_scroll)?.isChecked = true
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_auto_scroll -> {
                item.isChecked = !item.isChecked
                true
            }
            R.id.action_clear -> {
                messageList.clear()
                lastQuery = null
                messageAdapter.notifyDataSetChanged()
                true
            }
            R.id.action_repeat -> {
                lastQuery?.let { getChatResponse(it) }
                    ?: Toast.makeText(this, "No previous query to repeat", Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_share -> {
                if (messageList.isNotEmpty()) {
                    shareMessage(messageList.last())
                } else {
                    Toast.makeText(this, "No messages to share", Toast.LENGTH_SHORT).show()
                }
                true
            }
            R.id.action_logout -> {
                AuthManager.logout(this)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
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

    private fun showMessageOptionsDialog(position: Int) {
        if (position < 0 || position >= messageList.size) return
        val message = messageList[position]
        val options = arrayOf("Delete", "Share", "Copy")
        AlertDialog.Builder(this)
            .setTitle("Message Options")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        messageList.removeAt(position)
                        messageAdapter.notifyItemRemoved(position)
                        messageAdapter.notifyItemRangeChanged(position, messageList.size)
                    }
                    1 -> shareMessage(message)
                    2 -> copyMessage(message)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareMessage(message: Message) {
        val shareText = "${message.text}\n[${message.timestamp}]"
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        startActivity(Intent.createChooser(shareIntent, "Share Message"))
    }

    private fun copyMessage(message: Message) {
        val copyText = "${message.text}\n[${message.timestamp}]"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Message", copyText)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Message copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun encryptAudio(audio: ByteArray): ByteArray {
        return RetrofitClient.encryptAudio(audio, sessionKey)
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        audioRecord = AudioRecord(
            AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        audioFile = File(cacheDir, "temp_audio.wav")
        val audioBuffer = ByteArray(bufferSize)
        val recordedData = mutableListOf<Byte>()

        isRecording = true
        recordingStartTime = System.currentTimeMillis()
        audioRecord?.startRecording()

        Thread {
            try {
                while (isRecording) {
                    val bytesRead = audioRecord?.read(audioBuffer, 0, bufferSize) ?: 0
                    if (bytesRead > 0) {
                        recordedData.addAll(audioBuffer.take(bytesRead))
                        val rms = calculateRMS(audioBuffer, bytesRead)
                        runOnUiThread {
                            audioLevelBar.progress = (rms * 100).toInt().coerceIn(0, 100)
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Recording failed", Toast.LENGTH_SHORT).show()
                }
            } finally {
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
                val duration = System.currentTimeMillis() - recordingStartTime
                if (duration >= MIN_RECORDING_DURATION_MS) {
                    writeWavFile(recordedData.toByteArray())
                    audioFile?.let { sendAudioToApi(it) }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "Recording too short", Toast.LENGTH_SHORT).show()
                    }
                    audioFile?.delete()
                }
            }
        }.start()
    }

    private fun calculateRMS(buffer: ByteArray, bytesRead: Int): Float {
        var sum = 0L
        for (i in 0 until bytesRead step 2) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            sum += sample * sample
        }
        val meanSquare = sum / (bytesRead / 2)
        return (Math.sqrt(meanSquare.toDouble()) / 32768.0).toFloat()
    }

    private fun stopRecording() {
        isRecording = false
    }

    private fun writeWavFile(pcmData: ByteArray) {
        audioFile?.let { file ->
            FileOutputStream(file).use { fos ->
                val totalAudioLen = pcmData.size
                val totalDataLen = totalAudioLen + 36
                val channels = 1
                val sampleRate = SAMPLE_RATE
                val bitsPerSample = 16
                val byteRate = sampleRate * channels * bitsPerSample / 8

                val header = ByteBuffer.allocate(44)
                header.order(ByteOrder.LITTLE_ENDIAN)
                header.put("RIFF".toByteArray())
                header.putInt(totalDataLen)
                header.put("WAVE".toByteArray())
                header.put("fmt ".toByteArray())
                header.putInt(16)
                header.putShort(1.toShort())
                header.putShort(channels.toShort())
                header.putInt(sampleRate)
                header.putInt(byteRate)
                header.putShort((channels * bitsPerSample / 8).toShort())
                header.putShort(bitsPerSample.toShort())
                header.put("data".toByteArray())
                header.putInt(totalAudioLen)

                fos.write(header.array())
                fos.write(pcmData)
            }
        }
    }

    private fun sendAudioToApi(audioFile: File) {
        val token = AuthManager.getToken(this) ?: return
        val selectedLanguage = prefs.getString("language", "kannada") ?: "kannada"
        // Map to supported languages to match server validation
        val supportedLanguage = when (selectedLanguage.lowercase()) {
            "hindi" -> "hindi"
            "tamil" -> "tamil"
            else -> "kannada" // Default to kannada
        }

        // Encrypt audio file
        val audioBytes = audioFile.readBytes()
        val encryptedAudio = try {
            encryptAudio(audioBytes)
        } catch (e: Exception) {
            Log.e("AnswerActivity", "Audio encryption failed: ${e.message}")
            runOnUiThread {
                Toast.makeText(this, "Encryption error", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val encryptedFile = File(cacheDir, "encrypted_${audioFile.name}")
        FileOutputStream(encryptedFile).use { it.write(encryptedAudio) }

        // Encrypt language
        val encryptedLanguage = try {
            RetrofitClient.encryptText(supportedLanguage, sessionKey)
        } catch (e: Exception) {
            Log.e("AnswerActivity", "Language encryption failed: ${e.message}")
            runOnUiThread {
                Toast.makeText(this, "Encryption error", Toast.LENGTH_SHORT).show()
            }
            return
        }
        Log.d("AnswerActivity", "Transcribe request - file: ${encryptedFile.name}, language: $encryptedLanguage")

        val requestFile = encryptedFile.asRequestBody("application/octet-stream".toMediaType())
        val filePart = MultipartBody.Part.createFormData("file", encryptedFile.name, requestFile)

        lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            try {
                val cleanSessionKey = Base64.encodeToString(sessionKey, Base64.NO_WRAP)
                val response = RetrofitClient.apiService(this@AnswerActivity).transcribeAudio(
                    filePart,
                    encryptedLanguage,
                    "Bearer $token",
                    cleanSessionKey
                )
                val voiceQueryText = response.text
                val timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                if (voiceQueryText.isNotEmpty()) {
                    lastQuery = voiceQueryText
                    val message = Message("Voice Query: $voiceQueryText", timestamp, true)
                    messageList.add(message)
                    messageAdapter.notifyItemInserted(messageList.size - 1)
                    historyRecyclerView.requestLayout()
                    scrollToLatestMessage()
                    getChatResponse(voiceQueryText)
                } else {
                    Toast.makeText(this@AnswerActivity, "Voice query empty", Toast.LENGTH_SHORT).show()
                }
            } catch (e: retrofit2.HttpException) {
                Log.e("AnswerActivity", "Transcription failed: ${e.message}, response: ${e.response()?.errorBody()?.string()}")
                Toast.makeText(this@AnswerActivity, "Voice query error: ${e.message}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e("AnswerActivity", "Transcription failed: ${e.message}", e)
                Toast.makeText(this@AnswerActivity, "Voice query error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                progressBar.visibility = View.GONE
                audioFile?.delete()
                encryptedFile.delete()
            }
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

        // Encrypt all fields
        val encryptedPrompt = try {
            RetrofitClient.encryptText(prompt, sessionKey)
        } catch (e: Exception) {
            Log.e("AnswerActivity", "Prompt encryption failed: ${e.message}")
            Toast.makeText(this, "Encryption error", Toast.LENGTH_SHORT).show()
            return
        }
        val encryptedSrcLang = try {
            RetrofitClient.encryptText(srcLang, sessionKey)
        } catch (e: Exception) {
            Log.e("AnswerActivity", "Source language encryption failed: ${e.message}")
            Toast.makeText(this, "Encryption error", Toast.LENGTH_SHORT).show()
            return
        }
        val encryptedTgtLang = try {
            RetrofitClient.encryptText(tgtLang, sessionKey)
        } catch (e: Exception) {
            Log.e("AnswerActivity", "Target language encryption failed: ${e.message}")
            Toast.makeText(this, "Encryption error", Toast.LENGTH_SHORT).show()
            return
        }
        Log.d("AnswerActivity", "Chat request - prompt: $encryptedPrompt, src_lang: $encryptedSrcLang, tgt_lang: $encryptedTgtLang")

        val chatRequest = ChatRequest(encryptedPrompt, encryptedSrcLang, encryptedTgtLang)

        lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            try {
                val cleanSessionKey = Base64.encodeToString(sessionKey, Base64.NO_WRAP)
                val response = RetrofitClient.apiService(this@AnswerActivity).chat(
                    chatRequest,
                    "Bearer $token",
                    cleanSessionKey
                )
                val answerText = response.response
                val timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val message = Message("Answer: $answerText", timestamp, false)
                messageList.add(message)
                messageAdapter.notifyItemInserted(messageList.size - 1)
                historyRecyclerView.requestLayout()
                scrollToLatestMessage()

                // Encrypt text for TTS
                val encryptedAnswerText = try {
                    RetrofitClient.encryptText(answerText, sessionKey)
                } catch (e: Exception) {
                    Log.e("AnswerActivity", "TTS text encryption failed: ${e.message}")
                    ""
                }
                if (encryptedAnswerText.isNotEmpty()) {
                    SpeechUtils.textToSpeech(
                        context = this@AnswerActivity,
                        scope = lifecycleScope,
                        text = encryptedAnswerText,
                        message = message,
                        recyclerView = historyRecyclerView,
                        adapter = messageAdapter,
                        ttsProgressBarVisibility = { visible ->
                            ttsProgressBar.visibility = if (visible) View.VISIBLE else View.GONE
                        },
                        srcLang = tgtLang,
                        sessionKey = sessionKey
                    )
                }
            } catch (e: retrofit2.HttpException) {
                Log.e("AnswerActivity", "Chat failed: ${e.message}, response: ${e.response()?.errorBody()?.string()}")
                Toast.makeText(this@AnswerActivity, "Chat error: ${e.message}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e("AnswerActivity", "Chat failed: ${e.message}", e)
                Toast.makeText(this@AnswerActivity, "Chat error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun scrollToLatestMessage() {
        val autoScrollEnabled = toolbar.menu.findItem(R.id.action_auto_scroll)?.isChecked ?: false
        if (autoScrollEnabled && messageList.isNotEmpty()) {
            historyRecyclerView.post {
                val itemCount = messageAdapter.itemCount
                if (itemCount > 0) {
                    historyRecyclerView.smoothScrollToPosition(itemCount - 1)
                }
            }
        }
    }

    private fun toggleAudioPlayback(message: Message, button: ImageButton) {
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

    private fun isValidBase64(str: String): Boolean {
        return str.matches(Regex("^[A-Za-z0-9+/=]+$"))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_PERMISSION_CODE && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // Permission granted
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sessionDialog?.dismiss()
        sessionDialog = null
        mediaPlayer?.release()
        mediaPlayer = null
        messageList.forEach { it.audioFile?.delete() }
        audioRecord?.release()
        audioRecord = null
        audioFile?.delete()
    }
}