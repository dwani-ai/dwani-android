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
import java.io.FileInputStream

class MainActivity : AppCompatActivity() {

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
    private val MIN_RECORDING_DURATION_MS = 1000L // Minimum duration: 1 second
    private var recordingStartTime: Long = 0L
    private val messageList = mutableListOf<Message>()
    private lateinit var messageAdapter: MessageAdapter
    private var lastQuery: String? = null
    private var currentTheme: Boolean? = null
    private lateinit var ttsProgressBar: android.widget.ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val isDarkTheme = prefs.getBoolean("dark_theme", false)
        if (currentTheme == null || currentTheme != isDarkTheme) {
            setTheme(if (isDarkTheme) R.style.Theme_DhwaniVoiceAI_Dark else R.style.Theme_DhwaniVoiceAI_Light)
            currentTheme = isDarkTheme
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        historyRecyclerView = findViewById(R.id.historyRecyclerView)
        audioLevelBar = findViewById(R.id.audioLevelBar)
        progressBar = findViewById(R.id.progressBar)
        pushToTalkFab = findViewById(R.id.pushToTalkFab)
        textQueryInput = findViewById(R.id.textQueryInput)
        sendButton = findViewById(R.id.sendButton)
        toolbar = findViewById(R.id.toolbar)
        ttsProgressBar = findViewById(R.id.ttsProgressBar)

        setSupportActionBar(toolbar)

        messageAdapter = MessageAdapter(messageList) { position ->
            showMessageOptionsDialog(position)
        }
        historyRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = messageAdapter
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
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
                if (toolbar.menu.findItem(R.id.action_auto_scroll).isChecked) {
                    historyRecyclerView.scrollToPosition(messageList.size - 1)
                }
                getChatResponse(query)
                textQueryInput.text.clear()
            } else {
                Toast.makeText(this, "Please enter a query", Toast.LENGTH_SHORT).show()
            }
        }
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
                    0 -> { // Delete
                        messageList.removeAt(position)
                        messageAdapter.notifyItemRemoved(position)
                        messageAdapter.notifyItemRangeChanged(position, messageList.size)
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

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
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
            try {
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
            } catch (e: IOException) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Failed to save WAV file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun sendAudioToApi(audioFile: File?) {
        if (audioFile == null) return

        runOnUiThread { progressBar.visibility = View.VISIBLE }

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val language = prefs.getString("language", "kannada") ?: "kannada"
        val maxRetries = prefs.getString("max_retries", "3")?.toIntOrNull() ?: 3
        val transcriptionApiEndpoint = prefs.getString("transcription_api_endpoint", "https://gaganyatri-asr-indic-server-cpu.hf.space/transcribe/") ?: "https://gaganyatri-asr-indic-server-cpu.hf.space/transcribe/"

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
                try {
                    val json = JSONObject(responseBody)
                    val voiceQueryText = json.optString("text", "")
                    val timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    if (voiceQueryText.isNotEmpty() && !json.has("error")) {
                        lastQuery = voiceQueryText
                        val message = Message("Voice Query: $voiceQueryText", timestamp, true)
                        runOnUiThread {
                            messageList.add(message)
                            messageAdapter.notifyItemInserted(messageList.size - 1)
                            if (toolbar.menu.findItem(R.id.action_auto_scroll).isChecked) {
                                historyRecyclerView.scrollToPosition(messageList.size - 1)
                            }
                            progressBar.visibility = View.GONE
                        }
                        getChatResponse(voiceQueryText)
                    } else {
                        runOnUiThread {
                            Toast.makeText(this, "Voice Query empty or invalid, try again", Toast.LENGTH_SHORT).show()
                            progressBar.visibility = View.GONE
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this, "Failed to parse transcription: ${e.message}", Toast.LENGTH_SHORT).show()
                        progressBar.visibility = View.GONE
                    }
                }
            } else {
                runOnUiThread {
                    Toast.makeText(this, "Voice Query failed after $maxRetries retries", Toast.LENGTH_SHORT).show()
                    progressBar.visibility = View.GONE
                }
            }
        }.start()
    }

    private var mediaPlayer: MediaPlayer? = null

    private fun getChatResponse(prompt: String) {
        runOnUiThread { progressBar.visibility = View.VISIBLE }

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val maxRetries = prefs.getString("max_retries", "3")?.toIntOrNull() ?: 3
        val chatApiEndpoint = prefs.getString("chat_api_endpoint", "https://gaganyatri-llm-indic-server-cpu.hf.space/chat") ?: "https://gaganyatri-llm-indic-server-cpu.hf.space/chat"
        val chatApiKey = "your-new-secret-api-key" // Hardcoded for testing

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
                    if (toolbar.menu.findItem(R.id.action_auto_scroll).isChecked) {
                        historyRecyclerView.scrollToPosition(messageList.size - 1)
                    }
                    progressBar.visibility = View.GONE
                    // Call TTS after displaying the response
                    textToSpeech(answerText)
                }
            } ?: runOnUiThread {
                Toast.makeText(this, "Answer failed after $maxRetries retries", Toast.LENGTH_SHORT).show()
                progressBar.visibility = View.GONE
            }
        }.start()
    }

    private fun textToSpeech(text: String) {
        val ttsApiEndpoint = "https://gaganyatri-llm-indic-server-cpu.hf.space/v1/audio/speech"
        val apiKey = "your-new-secret-api-key"

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val jsonMediaType = "application/json".toMediaType()
        val requestBody = JSONObject().apply {
            put("input", text)
            put("voice", "kannada-female")
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

        Thread {
            try {
                runOnUiThread { ttsProgressBar.visibility = View.VISIBLE } // Before API call

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val audioBytes = response.body?.bytes()
                    runOnUiThread {
                        if (audioBytes != null && audioBytes.isNotEmpty()) {
                            val audioFile = File(cacheDir, "temp_tts_audio.mp3")
                            FileOutputStream(audioFile).use { fos ->
                                fos.write(audioBytes)
                            }
                            if (audioFile.exists() && audioFile.length() > 0) {
                                Toast.makeText(this, "Audio file created: ${audioFile.length()} bytes", Toast.LENGTH_SHORT).show()
                                playAudio(audioFile)
                            } else {
                                Toast.makeText(this, "Audio file creation failed", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(this, "TTS API returned empty audio data", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "TTS API failed: ${response.code} - ${response.message}", Toast.LENGTH_LONG).show()
                    }
                }
                runOnUiThread { ttsProgressBar.visibility = View.GONE }
            } catch (e: IOException) {
                runOnUiThread {
                    Toast.makeText(this, "TTS network error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun playAudio(audioFile: File) {
        try {
            if (!audioFile.exists()) {
                Toast.makeText(this, "Audio file doesn't exist", Toast.LENGTH_SHORT).show()
                return
            }

            mediaPlayer?.release() // Release any existing player
            mediaPlayer = MediaPlayer()
            mediaPlayer?.apply {
                setDataSource(FileInputStream(audioFile).fd)
                prepare()
                Toast.makeText(this@MainActivity, "Starting audio playback", Toast.LENGTH_SHORT).show()
                start()
                setOnCompletionListener {
                    Toast.makeText(this@MainActivity, "Audio playback completed", Toast.LENGTH_SHORT).show()
                    it.release()
                    mediaPlayer = null
                    audioFile.delete()
                }
                setOnErrorListener { mp, what, extra ->
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "MediaPlayer error: what=$what, extra=$extra", Toast.LENGTH_LONG).show()
                    }
                    mp.release()
                    mediaPlayer = null
                    audioFile.delete()
                    true
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "Audio playback failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
            mediaPlayer?.release()
            mediaPlayer = null
            audioFile.delete()
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
    }
}

data class Message(val text: String, val timestamp: String, val isQuery: Boolean)

class MessageAdapter(
    private val messages: MutableList<Message>,
    private val onLongClick: (Int) -> Unit
) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val messageText: TextView = itemView.findViewById(R.id.messageText)
        val timestampText: TextView = itemView.findViewById(R.id.timestampText)
        val messageContainer: LinearLayout = itemView.findViewById(R.id.messageContainer)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        holder.messageText.text = message.text
        holder.timestampText.text = message.timestamp

        // Align queries (isQuery = true) to the right, answers to the left
        val layoutParams = holder.messageContainer.layoutParams as LinearLayout.LayoutParams
        layoutParams.gravity = if (message.isQuery) android.view.Gravity.END else android.view.Gravity.START
        holder.messageContainer.layoutParams = layoutParams

        // Set bubble color: green for queries (right), white for answers (left)
        holder.messageContainer.isActivated = !message.isQuery // White for answers, green for queries

        val animation = AnimationUtils.loadAnimation(holder.itemView.context, android.R.anim.fade_in)
        holder.itemView.startAnimation(animation)

        holder.itemView.setOnLongClickListener {
            onLongClick(position)
            true
        }
    }

    override fun getItemCount(): Int = messages.size
}