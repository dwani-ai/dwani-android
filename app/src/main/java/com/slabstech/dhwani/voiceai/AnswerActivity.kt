package com.slabstech.dhwani.voiceai

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder.AudioSource
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.appcompat.widget.Toolbar
import android.view.Menu
import android.view.MenuItem
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.widget.LinearLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.appbar.AppBarLayout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import android.media.MediaPlayer
import android.text.Editable
import java.io.FileInputStream

class AnswerActivity : AppCompatActivity() {

    private val RECORD_AUDIO_PERMISSION_CODE = 100
    private var audioRecord: AudioRecord? = null
    private var audioFile: File? = null
    private lateinit var historyRecyclerView: RecyclerView
    private lateinit var audioLevelBar: android.widget.ProgressBar
    private lateinit var progressBar: android.widget.ProgressBar
    private lateinit var pushToTalkFab: FloatingActionButton
    private lateinit var textQueryInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var toolbar: Toolbar
    private var isRecording = false
    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val RETRY_DELAY_MS = 2000L
    private val MIN_RECORDING_DURATION_MS = 1000L
    private var recordingStartTime: Long = 0L
    private val messageList = mutableListOf<Message>()
    private lateinit var messageAdapter: MessageAdapter
    private var lastQuery: String? = null
    private var currentTheme: Boolean? = null
    private lateinit var ttsProgressBar: android.widget.ProgressBar
    private val AUTO_PLAY_KEY = "auto_play_tts"

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val isDarkTheme = prefs.getBoolean("dark_theme", false)
        setTheme(if (isDarkTheme) R.style.Theme_DhwaniVoiceAI_Dark else R.style.Theme_DhwaniVoiceAI_Light)
        currentTheme = isDarkTheme

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_answer)

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
            android.util.Log.d("AnswerActivity", "RecyclerView initialized, Adapter item count: ${messageAdapter.itemCount}")

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
                try {
                    val query = textQueryInput.text.toString().trim()
                    if (query.isNotEmpty()) {
                        val timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                        val message = Message("Query: $query", timestamp, true)
                        messageList.add(message)
                        messageAdapter.notifyItemInserted(messageList.size - 1)
                        historyRecyclerView.requestLayout() // Force layout update
                        scrollToLatestMessage()
                        getChatResponse(query)
                        textQueryInput.text.clear()
                    } else {
                        Toast.makeText(this, "Please enter a query", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("AnswerActivity", "Crash in sendButton click: ${e.message}", e)
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
                    else -> false
                }
            }
            bottomNavigation.selectedItemId = R.id.nav_answer
        } catch (e: Exception) {
            android.util.Log.e("AnswerActivity", "Crash in onCreate: ${e.message}", e)
            Toast.makeText(this, "Initialization failed: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        menu.findItem(R.id.action_auto_scroll)?.isChecked = true // Enable by default
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
                android.util.Log.d("AnswerActivity", "Message list cleared, List Size: ${messageList.size}")
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
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val isDarkTheme = prefs.getBoolean("dark_theme", false)
        if (currentTheme != isDarkTheme) {
            currentTheme = isDarkTheme
            recreate()
        }
    }

    private fun animateFabRecordingStart() {
        try {
            pushToTalkFab.setImageResource(android.R.drawable.ic_media_pause)
            val scaleUp = ObjectAnimator.ofPropertyValuesHolder(
                pushToTalkFab,
                PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.2f),
                PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.2f)
            )
            scaleUp.duration = 200
            scaleUp.start()
            pushToTalkFab.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_red_light)
        } catch (e: Exception) {
            android.util.Log.e("AnswerActivity", "Crash in animateFabRecordingStart: ${e.message}", e)
        }
    }

    private fun animateFabRecordingStop() {
        try {
            pushToTalkFab.setImageResource(R.drawable.ic_mic)
            val scaleDown = ObjectAnimator.ofPropertyValuesHolder(
                pushToTalkFab,
                PropertyValuesHolder.ofFloat("scaleX", 1.2f, 1.0f),
                PropertyValuesHolder.ofFloat("scaleY", 1.2f, 1.0f)
            )
            scaleDown.duration = 200
            scaleDown.start()
            pushToTalkFab.backgroundTintList = ContextCompat.getColorStateList(this, R.color.whatsapp_green)
        } catch (e: Exception) {
            android.util.Log.e("AnswerActivity", "Crash in animateFabRecordingStop: ${e.message}", e)
        }
    }

    private fun showMessageOptionsDialog(position: Int) {
        try {
            if (position < 0 || position >= messageList.size) return

            val message = messageList[position]
            val options = arrayOf("Delete", "Share", "Copy")
            AlertDialog.Builder(this)
                .setTitle("Message Options")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> { // Delete
                            messageList.removeAt(position)
                            messageAdapter.notifyItemRemoved(position)
                            messageAdapter.notifyItemRangeChanged(position, messageList.size)
                            android.util.Log.d("AnswerActivity", "Message deleted at position: $position, List Size: ${messageList.size}")
                        }
                        1 -> { // Share
                            shareMessage(message)
                        }
                        2 -> { // Copy
                            copyMessage(message)
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        } catch (e: Exception) {
            android.util.Log.e("AnswerActivity", "Crash in showMessageOptionsDialog: ${e.message}", e)
        }
    }

    private fun shareMessage(message: Message) {
        try {
            val shareText = "${message.text}\n[${message.timestamp}]"
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
            startActivity(Intent.createChooser(shareIntent, "Share Message"))
        } catch (e: Exception) {
            android.util.Log.e("AnswerActivity", "Crash in shareMessage: ${e.message}", e)
        }
    }

    private fun copyMessage(message: Message) {
        try {
            val copyText = "${message.text}\n[${message.timestamp}]"
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Message", copyText)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Message copied to clipboard", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.util.Log.e("AnswerActivity", "Crash in copyMessage: ${e.message}", e)
        }
    }

    private fun startRecording() {
        try {
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
                    e.printStackTrace()
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
                        if (audioFile != null && audioFile!!.exists()) {
                            sendAudioToApi(audioFile)
                        }
                    } else {
                        runOnUiThread {
                            Toast.makeText(this, "Recording too short, try again", Toast.LENGTH_SHORT).show()
                        }
                        audioFile?.delete()
                    }
                }
            }.start()
        } catch (e: Exception) {
            android.util.Log.e("AnswerActivity", "Crash in startRecording: ${e.message}", e)
        }
    }

    private fun calculateRMS(buffer: ByteArray, bytesRead: Int): Float {
        try {
            var sum = 0L
            for (i in 0 until bytesRead step 2) {
                val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                sum += sample * sample
            }
            val meanSquare = sum / (bytesRead / 2)
            return (Math.sqrt(meanSquare.toDouble()) / 32768.0).toFloat()
        } catch (e: Exception) {
            android.util.Log.e("AnswerActivity", "Crash in calculateRMS: ${e.message}", e)
            return 0f
        }
    }

    private fun stopRecording() {
        isRecording = false
    }

    private fun writeWavFile(pcmData: ByteArray) {
        try {
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
        } catch (e: IOException) {
            e.printStackTrace()
            runOnUiThread {
                Toast.makeText(this, "Failed to save WAV file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendAudioToApi(audioFile: File?) {
        if (audioFile == null) return

        runOnUiThread { progressBar.visibility = View.VISIBLE }

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val language = prefs.getString("language", "kannada") ?: "kannada"
        val maxRetries = prefs.getString("max_retries", "3")?.toIntOrNull() ?: 3
        val transcriptionApiEndpoint = prefs.getString("transcription_api_endpoint", "https://gaganyatri-llm-indic-server-vlm.hf.space/transcribe/") ?: "https://gaganyatri-llm-indic-server-vlm.hf.space/transcribe/"

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", audioFile.name,
                audioFile.asRequestBody("audio/x-wav".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("$transcriptionApiEndpoint?language=$language")
            .header("accept", "application/json")
            .post(requestBody)
            .build()

        Thread {
            try {
                var attempts = 0
                var success = false
                var responseBody: String? = null

                while (attempts < maxRetries && !success) {
                    try {
                        val response = client.newCall(request).execute()
                        if (response.isSuccessful) {
                            responseBody = response.body?.string()
                            success = true
                        } else {
                            attempts++
                            if (attempts < maxRetries) Thread.sleep(RETRY_DELAY_MS)
                            runOnUiThread {
                                Toast.makeText(this, "Transcription API failed: ${response.code}, retrying ($attempts/$maxRetries)", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: IOException) {
                        e.printStackTrace()
                        attempts++
                        if (attempts < maxRetries) Thread.sleep(RETRY_DELAY_MS)
                        runOnUiThread {
                            Toast.makeText(this, "Network error: ${e.message}, retrying ($attempts/$maxRetries)", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                if (success && responseBody != null) {
                    val json = JSONObject(responseBody)
                    val voiceQueryText = json.optString("text", "")
                    val timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    if (voiceQueryText.isNotEmpty() && !json.has("error")) {
                        lastQuery = voiceQueryText
                        val message = Message("Voice Query: $voiceQueryText", timestamp, true)
                        runOnUiThread {
                            messageList.add(message)
                            messageAdapter.notifyItemInserted(messageList.size - 1)
                            historyRecyclerView.requestLayout() // Force layout update
                            scrollToLatestMessage()
                            progressBar.visibility = View.GONE
                        }
                        getChatResponse(voiceQueryText)
                    }  else {
                        runOnUiThread {
                            Toast.makeText(this, "Voice Query empty or invalid, try again", Toast.LENGTH_SHORT).show()
                            progressBar.visibility = View.GONE
                        }
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "Voice Query failed after $maxRetries retries", Toast.LENGTH_SHORT).show()
                        progressBar.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AnswerActivity", "Crash in sendAudioToApi: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(this, "Voice query error: ${e.message}", Toast.LENGTH_LONG).show()
                    progressBar.visibility = View.GONE
                }
            }
        }.start()
    }

    private var mediaPlayer: MediaPlayer? = null

    private fun scrollToLatestMessage() {
        val autoScrollEnabled = toolbar.menu.findItem(R.id.action_auto_scroll)?.isChecked ?: false
        if (autoScrollEnabled && messageList.isNotEmpty()) {
            historyRecyclerView.post {
                val itemCount = messageAdapter.itemCount
                if (itemCount > 0) {
                    historyRecyclerView.smoothScrollToPosition(itemCount - 1)
                    android.util.Log.d("AnswerActivity", "Scrolled to position: ${itemCount - 1}")
                } else {
                    android.util.Log.w("AnswerActivity", "No items to scroll to")
                }
            }
        } else {
            android.util.Log.d("AnswerActivity", "Auto-scroll disabled or message list empty")
        }
    }

    private fun getChatResponse(prompt: String) {
        runOnUiThread { progressBar.visibility = View.VISIBLE }

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val maxRetries = prefs.getString("max_retries", "3")?.toIntOrNull() ?: 3
        val chatApiEndpoint = prefs.getString("chat_api_endpoint", "https://gaganyatri-llm-indic-server-vlm.hf.space/chat") ?: "https://gaganyatri-llm-indic-server-vlm.hf.space/chat"
        val chatApiKey = prefs.getString("chat_api_key", "your-new-secret-api-key") ?: "your-new-secret-api-key"
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val jsonMediaType = "application/json".toMediaType()
        val requestBody = JSONObject().put("prompt", prompt).toString().toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url(chatApiEndpoint)
            .header("accept", "application/json")
            .header("X-API-Key", chatApiKey)
            .post(requestBody)
            .build()

        Thread {
            try {
                var attempts = 0
                var success = false
                var responseBody: String? = null

                while (attempts < maxRetries && !success) {
                    try {
                        val response = client.newCall(request).execute()
                        if (response.isSuccessful) {
                            responseBody = response.body?.string()
                            success = true
                        } else {
                            attempts++
                            if (attempts < maxRetries) Thread.sleep(RETRY_DELAY_MS)
                            runOnUiThread {
                                Toast.makeText(this, "Chat API failed: ${response.code}, retrying ($attempts/$maxRetries)", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: IOException) {
                        e.printStackTrace()
                        attempts++
                        if (attempts < maxRetries) Thread.sleep(RETRY_DELAY_MS)
                        runOnUiThread {
                            Toast.makeText(this, "Network error: ${e.message}, retrying ($attempts/$maxRetries)", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                responseBody?.let {
                    val json = JSONObject(it)
                    val answerText = json.getString("response")
                    val timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    val message = Message("Answer: $answerText", timestamp, false)
                    runOnUiThread {
                        messageList.add(message)
                        messageAdapter.notifyItemInserted(messageList.size - 1)
                        historyRecyclerView.requestLayout() // Force layout update
                        scrollToLatestMessage()
                        progressBar.visibility = View.GONE
                        textToSpeech(answerText, message)
                    }
                } ?: runOnUiThread {
                    Toast.makeText(this, "Answer failed after $maxRetries retries", Toast.LENGTH_SHORT).show()
                    progressBar.visibility = View.GONE
                }
            } catch (e: Exception) {
                android.util.Log.e("AnswerActivity", "Crash in getChatResponse: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(this, "Chat response error: ${e.message}", Toast.LENGTH_LONG).show()
                    progressBar.visibility = View.GONE
                }
            }
        }.start()
    }

    private fun textToSpeech(text: String, message: Message) {
        // Temporarily disabled to isolate crash issues - uncomment to re-enable after testing
        //return


        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.getBoolean("tts_enabled", false)) return
        val autoPlay = prefs.getBoolean(AUTO_PLAY_KEY, true)

        val voice = prefs.getString("tts_voice", "Anu speaks with a high pitch at a normal pace in a clear, close-sounding environment. Her neutral tone is captured with excellent audio quality.")
            ?: "Anu speaks with a high pitch at a normal pace in a clear, close-sounding environment. Her neutral tone is captured with excellent audio quality."
        val ttsApiEndpoint = "https://gaganyatri-llm-indic-server-vlm.hf.space/v1/audio/speech"
        val apiKey = prefs.getString("chat_api_key", "your-new-secret-api-key") ?: "your-new-secret-api-key"

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val jsonMediaType = "application/json".toMediaType()
        val requestBody = JSONObject().apply {
            put("input", text)
            put("voice", voice)
            put("model", "ai4bharat/indic-parler-tts")
            put("response_format", "mp3")
            put("speed", 1.0)
        }.toString().toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url(ttsApiEndpoint)
            .header("X-API-Key", apiKey)
            .header("Content-Type", "application/json")
            .post(requestBody)
            .build()

        runOnUiThread { ttsProgressBar.visibility = View.VISIBLE }

        Thread {
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val audioBytes = response.body?.bytes()
                    runOnUiThread {
                        try {
                            if (audioBytes != null && audioBytes.isNotEmpty()) {
                                val audioFile = File(cacheDir, "temp_tts_audio_${System.currentTimeMillis()}.mp3")
                                FileOutputStream(audioFile).use { fos ->
                                    fos.write(audioBytes)
                                }
                                if (audioFile.exists() && audioFile.length() > 0) {
                                    message.audioFile = audioFile
                                    ttsProgressBar.visibility = View.GONE
                                    val messageIndex = messageList.indexOf(message)
                                    if (messageIndex != -1) {
                                        messageAdapter.notifyItemChanged(messageIndex)
                                        android.util.Log.d("TextToSpeech", "Notified adapter for index: $messageIndex")
                                    }
                                    if (autoPlay) {
                                        playAudio(audioFile)
                                    }
                                } else {
                                    ttsProgressBar.visibility = View.GONE
                                    Toast.makeText(this, "Audio file creation failed", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                ttsProgressBar.visibility = View.GONE
                                Toast.makeText(this, "TTS API returned empty audio data", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("AnswerActivity", "Crash in textToSpeech UI update: ${e.message}", e)
                            ttsProgressBar.visibility = View.GONE
                            Toast.makeText(this, "TTS UI error: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    runOnUiThread {
                        ttsProgressBar.visibility = View.GONE
                        Toast.makeText(this, "TTS API failed: ${response.code} - ${response.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: IOException) {
                android.util.Log.e("AnswerActivity", "Crash in textToSpeech network call: ${e.message}", e)
                runOnUiThread {
                    ttsProgressBar.visibility = View.GONE
                    Toast.makeText(this, "TTS network error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()

    }

    private fun toggleAudioPlayback(message: Message, button: ImageButton) {
        try {
            message.audioFile?.let { audioFile ->
                if (mediaPlayer?.isPlaying == true) {
                    mediaPlayer?.stop()
                    mediaPlayer?.release()
                    mediaPlayer = null
                    button.setImageResource(android.R.drawable.ic_media_play)
                } else {
                    playAudio(audioFile)
                    button.setImageResource(R.drawable.ic_media_stop)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("AnswerActivity", "Crash in toggleAudioPlayback: ${e.message}", e)
        }
    }

    private fun playAudio(audioFile: File) {
        try {
            if (!audioFile.exists()) {
                Toast.makeText(this, "Audio file doesn't exist", Toast.LENGTH_SHORT).show()
                return
            }

            mediaPlayer?.release()
            mediaPlayer = MediaPlayer()
            mediaPlayer?.apply {
                setDataSource(FileInputStream(audioFile).fd)
                prepare()
                start()
                val messageIndex = messageList.indexOfFirst { it.audioFile == audioFile }
                if (messageIndex != -1) {
                    val holder = historyRecyclerView.findViewHolderForAdapterPosition(messageIndex) as? MessageAdapter.MessageViewHolder
                    holder?.audioControlButton?.setImageResource(R.drawable.ic_media_stop)
                }
                setOnCompletionListener {
                    it.release()
                    mediaPlayer = null
                    if (messageIndex != -1) {
                        val holder = historyRecyclerView.findViewHolderForAdapterPosition(messageIndex) as? MessageAdapter.MessageViewHolder
                        holder?.audioControlButton?.setImageResource(android.R.drawable.ic_media_play)
                    }
                }
                setOnErrorListener { mp, what, extra ->
                    runOnUiThread {
                        Toast.makeText(this@AnswerActivity, "MediaPlayer error: what=$what, extra=$extra", Toast.LENGTH_LONG).show()
                    }
                    mp.release()
                    mediaPlayer = null
                    if (messageIndex != -1) {
                        val holder = historyRecyclerView.findViewHolderForAdapterPosition(messageIndex) as? MessageAdapter.MessageViewHolder
                        holder?.audioControlButton?.setImageResource(android.R.drawable.ic_media_play)
                    }
                    true
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("AnswerActivity", "Crash in playAudio: ${e.message}", e)
            runOnUiThread {
                Toast.makeText(this, "Audio playback failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
            mediaPlayer?.release()
            mediaPlayer = null
        }
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
        if (isRecording) {
            isRecording = false
        }
        audioRecord?.release()
        audioRecord = null
        mediaPlayer?.release()
        mediaPlayer = null
        messageList.forEach { it.audioFile?.delete() }
    }

    data class Message(
        val text: String,
        val timestamp: String,
        val isQuery: Boolean,
        var audioFile: File? = null
    )

    class MessageAdapter(
        private val messages: MutableList<Message>,
        private val onLongClick: (Int) -> Unit,
        private val onAudioControlClick: (Message, ImageButton) -> Unit
    ) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

        class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val messageText: TextView = itemView.findViewById(R.id.messageText)
            val timestampText: TextView = itemView.findViewById(R.id.timestampText)
            val messageContainer: LinearLayout = itemView.findViewById(R.id.messageContainer)
            val audioControlButton: ImageButton = itemView.findViewById(R.id.audioControlButton)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
            return MessageViewHolder(view)
        }

        override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
            try {
                val message = messages[position]
                holder.messageText.text = message.text
                holder.timestampText.text = message.timestamp

                val layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                layoutParams.gravity = if (message.isQuery) android.view.Gravity.END else android.view.Gravity.START
                holder.messageContainer.layoutParams = layoutParams

                // Apply the bubble background and tint
                val backgroundDrawable = ContextCompat.getDrawable(holder.itemView.context, R.drawable.whatsapp_message_bubble)?.mutate()
                backgroundDrawable?.let {
                    it.setTint(ContextCompat.getColor(
                        holder.itemView.context,
                        if (message.isQuery) R.color.whatsapp_message_out else R.color.whatsapp_message_in
                    ))
                    holder.messageText.background = it
                    // Add slight elevation or padding to make white visible against beige background
                    holder.messageText.elevation = 2f // Optional: adds shadow for contrast
                    holder.messageText.setPadding(16, 16, 16, 16) // Adjust padding for WhatsApp-like spacing
                }

                holder.messageText.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.whatsapp_text))
                holder.timestampText.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.whatsapp_timestamp))

                if (!message.isQuery && message.audioFile != null) {
                    holder.audioControlButton.visibility = View.VISIBLE
                    holder.audioControlButton.setOnClickListener {
                        onAudioControlClick(message, holder.audioControlButton)
                    }
                    holder.audioControlButton.setColorFilter(ContextCompat.getColor(holder.itemView.context, R.color.whatsapp_green))
                } else {
                    holder.audioControlButton.visibility = View.GONE
                }

                val animation = AnimationUtils.loadAnimation(holder.itemView.context, android.R.anim.fade_in)
                holder.itemView.startAnimation(animation)

                holder.itemView.setOnLongClickListener {
                    onLongClick(position)
                    true
                }
            } catch (e: Exception) {
                android.util.Log.e("MessageAdapter", "Crash in onBindViewHolder at position $position: ${e.message}", e)
            }
        }
        override fun getItemCount(): Int {
            val count = messages.size
            android.util.Log.d("MessageAdapter", "getItemCount called, returning: $count")
            return count
        }
    }
}