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
import android.widget.*
import android.text.Editable
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class TranslateActivity : AppCompatActivity() {

    private val RECORD_AUDIO_PERMISSION_CODE = 100
    private var audioRecord: AudioRecord? = null
    private var audioFile: File? = null
    private lateinit var historyRecyclerView: RecyclerView
    private lateinit var audioLevelBar: ProgressBar
    private lateinit var progressBar: ProgressBar
    private lateinit var pushToTalkFab: FloatingActionButton
    private lateinit var textQueryInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var targetLanguageSpinner: Spinner
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

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val isDarkTheme = prefs.getBoolean("dark_theme", false)
        setTheme(if (isDarkTheme) R.style.Theme_DhwaniVoiceAI_Dark else R.style.Theme_DhwaniVoiceAI_Light)
        currentTheme = isDarkTheme

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_translate)

        try {
            historyRecyclerView = findViewById(R.id.historyRecyclerView)
            audioLevelBar = findViewById(R.id.audioLevelBar)
            progressBar = findViewById(R.id.progressBar)
            pushToTalkFab = findViewById(R.id.pushToTalkFab)
            textQueryInput = findViewById(R.id.textQueryInput)
            sendButton = findViewById(R.id.sendButton)
            targetLanguageSpinner = findViewById(R.id.targetLanguageSpinner)
            toolbar = findViewById(R.id.toolbar)
            val bottomNavigation = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNavigation)

            setSupportActionBar(toolbar)

            messageAdapter = MessageAdapter(messageList, { position ->
                showMessageOptionsDialog(position)
            }, { _, _ -> }) // No audio playback for translations
            historyRecyclerView.apply {
                layoutManager = LinearLayoutManager(this@TranslateActivity)
                adapter = messageAdapter
                visibility = View.VISIBLE
                setBackgroundColor(ContextCompat.getColor(this@TranslateActivity, android.R.color.transparent))
            }
            android.util.Log.d("TranslateActivity", "RecyclerView initialized, Adapter item count: ${messageAdapter.itemCount}")

            // Setup Spinner
            val adapter = ArrayAdapter.createFromResource(
                this,
                R.array.target_languages,
                android.R.layout.simple_spinner_item
            )
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            targetLanguageSpinner.adapter = adapter

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
                        val message = Message("Input: $query", timestamp, true)
                        messageList.add(message)
                        messageAdapter.notifyItemInserted(messageList.size - 1)
                        historyRecyclerView.requestLayout()
                        scrollToLatestMessage()
                        getTranslationResponse(query)
                        textQueryInput.text.clear()
                    } else {
                        Toast.makeText(this, "Please enter a sentence", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("TranslateActivity", "Crash in sendButton click: ${e.message}", e)
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
                    R.id.nav_answer -> {
                        AlertDialog.Builder(this)
                            .setMessage("Switch to Answer?")
                            .setPositiveButton("Yes") { _, _ ->
                                startActivity(Intent(this, AnswerActivity::class.java))
                            }
                            .setNegativeButton("No", null)
                            .show()
                        false
                    }
                    R.id.nav_translate -> true
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
            bottomNavigation.selectedItemId = R.id.nav_translate
        } catch (e: Exception) {
            android.util.Log.e("TranslateActivity", "Crash in onCreate: ${e.message}", e)
            Toast.makeText(this, "Initialization failed: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
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
                lastQuery?.let { getTranslationResponse(it) }
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

    private fun sendAudioToApi(audioFile: File?) {
        if (audioFile == null) return

        runOnUiThread { progressBar.visibility = View.VISIBLE }

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val language = prefs.getString("language", "kannada") ?: "kannada" // Source language
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
                        }
                    } catch (e: IOException) {
                        attempts++
                        if (attempts < maxRetries) Thread.sleep(RETRY_DELAY_MS)
                    }
                }

                if (success && responseBody != null) {
                    val json = JSONObject(responseBody)
                    val voiceQueryText = json.optString("text", "")
                    val timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    if (voiceQueryText.isNotEmpty() && !json.has("error")) {
                        lastQuery = voiceQueryText
                        val message = Message("Voice Input: $voiceQueryText", timestamp, true)
                        runOnUiThread {
                            messageList.add(message)
                            messageAdapter.notifyItemInserted(messageList.size - 1)
                            historyRecyclerView.requestLayout()
                            scrollToLatestMessage()
                            progressBar.visibility = View.GONE
                        }
                        getTranslationResponse(voiceQueryText)
                    } else {
                        runOnUiThread {
                            Toast.makeText(this, "Voice Input empty or invalid", Toast.LENGTH_SHORT).show()
                            progressBar.visibility = View.GONE
                        }
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "Transcription failed after $maxRetries retries", Toast.LENGTH_SHORT).show()
                        progressBar.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Voice query error: ${e.message}", Toast.LENGTH_LONG).show()
                    progressBar.visibility = View.GONE
                }
            }
        }.start()
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

    private fun getTranslationResponse(input: String) {
        runOnUiThread { progressBar.visibility = View.VISIBLE }

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val srcLang = "kan_Knda" // Hardcoded for now; can be made configurable
        val tgtLang = resources.getStringArray(R.array.target_language_codes)[targetLanguageSpinner.selectedItemPosition]
        val maxRetries = prefs.getString("max_retries", "3")?.toIntOrNull() ?: 3
        val translateApiEndpoint = "https://gaganyatri-translate-indic-server-cpu.hf.space/translate?src_lang=$srcLang&tgt_lang=$tgtLang&device_type=cpu"

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val jsonMediaType = "application/json".toMediaType()
        val requestBody = JSONObject().apply {
            put("sentences", JSONArray().put(input))
            put("src_lang", srcLang)
            put("tgt_lang", tgtLang)
        }.toString().toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url(translateApiEndpoint)
            .header("accept", "application/json")
            .header("Content-Type", "application/json")
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
                        }
                    } catch (e: IOException) {
                        attempts++
                        if (attempts < maxRetries) Thread.sleep(RETRY_DELAY_MS)
                    }
                }

                if (success && responseBody != null) {
                    val json = JSONObject(responseBody)
                    val translations = json.getJSONArray("translations")
                    val translatedText = translations.getJSONObject(0).getString("tgt_text")
                    val timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    val message = Message("Translation: $translatedText", timestamp, false)
                    runOnUiThread {
                        messageList.add(message)
                        messageAdapter.notifyItemInserted(messageList.size - 1)
                        historyRecyclerView.requestLayout()
                        scrollToLatestMessage()
                        progressBar.visibility = View.GONE
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "Translation failed after $maxRetries retries", Toast.LENGTH_SHORT).show()
                        progressBar.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Translation error: ${e.message}", Toast.LENGTH_LONG).show()
                    progressBar.visibility = View.GONE
                }
            }
        }.start()
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
            val message = messages[position]
            holder.messageText.text = message.text
            holder.timestampText.text = message.timestamp

            val layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams.gravity = if (message.isQuery) android.view.Gravity.END else android.view.Gravity.START
            holder.messageContainer.layoutParams = layoutParams

            val backgroundDrawable = ContextCompat.getDrawable(holder.itemView.context, R.drawable.whatsapp_message_bubble)?.mutate()
            backgroundDrawable?.let {
                it.setTint(ContextCompat.getColor(
                    holder.itemView.context,
                    if (message.isQuery) R.color.whatsapp_message_out else R.color.whatsapp_message_in
                ))
                holder.messageText.background = it
                holder.messageText.elevation = 2f
                holder.messageText.setPadding(16, 16, 16, 16)
            }

            holder.messageText.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.whatsapp_text))
            holder.timestampText.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.whatsapp_timestamp))

            holder.audioControlButton.visibility = View.GONE // No audio for translations

            val animation = AnimationUtils.loadAnimation(holder.itemView.context, android.R.anim.fade_in)
            holder.itemView.startAnimation(animation)

            holder.itemView.setOnLongClickListener {
                onLongClick(position)
                true
            }
        }

        override fun getItemCount(): Int = messages.size
    }
}