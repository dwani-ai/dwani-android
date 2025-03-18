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
import android.util.Log
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
    private lateinit var ttsProgressBar: ProgressBar // Added for TTS
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
    private var mediaPlayer: MediaPlayer? = null // Added for TTS playback
    private val AUTO_PLAY_KEY = "auto_play_tts" // Added for auto-play setting
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        val isDarkTheme = prefs.getBoolean("dark_theme", false)
        setTheme(if (isDarkTheme) R.style.Theme_DhwaniVoiceAI_Dark else R.style.Theme_DhwaniVoiceAI_Light)
        currentTheme = isDarkTheme

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_translate)

        fetchAccessToken()

        try {
            historyRecyclerView = findViewById(R.id.historyRecyclerView)
            audioLevelBar = findViewById(R.id.audioLevelBar)
            progressBar = findViewById(R.id.progressBar)
            pushToTalkFab = findViewById(R.id.pushToTalkFab)
            textQueryInput = findViewById(R.id.textQueryInput)
            sendButton = findViewById(R.id.sendButton)
            toolbar = findViewById(R.id.toolbar)
            ttsProgressBar = findViewById(R.id.ttsProgressBar) // Initialize TTS ProgressBar
            val bottomNavigation = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNavigation)

            setSupportActionBar(toolbar)

            // Initialize TTS settings if not present
            if (!prefs.contains(AUTO_PLAY_KEY)) {
                prefs.edit().putBoolean(AUTO_PLAY_KEY, true).apply()
            }
            if (!prefs.contains("tts_enabled")) {
                prefs.edit().putBoolean("tts_enabled", false).apply()
            }

            messageAdapter = MessageAdapter(messageList, { position ->
                showMessageOptionsDialog(position)
            }, { message, button ->
                toggleAudioPlayback(message, button) // Enable audio control
            })
            historyRecyclerView.apply {
                layoutManager = LinearLayoutManager(this@TranslateActivity)
                adapter = messageAdapter
                visibility = View.VISIBLE
                setBackgroundColor(ContextCompat.getColor(this@TranslateActivity, android.R.color.transparent))
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
            Log.e("TranslateActivity", "Crash in onCreate: ${e.message}", e)
            Toast.makeText(this, "Initialization failed: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun fetchAccessToken() {
        lifecycleScope.launch {
            try {
                val response = RetrofitClient.apiService.login(LoginRequest("testuser", "password123"))
                prefs.edit().putString("access_token", response.access_token).apply()
                Log.d("TranslateActivity", "Token fetched: ${response.access_token}")
            } catch (e: Exception) {
                Log.e("TranslateActivity", "Token fetch failed: ${e.message}", e)
                Toast.makeText(this@TranslateActivity, "Authentication failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        menu.findItem(R.id.action_auto_scroll)?.isChecked = true

        val targetLanguageItem = menu.findItem(R.id.action_target_language)
        targetLanguageSpinner = targetLanguageItem.actionView as Spinner
        val adapter = ArrayAdapter.createFromResource(
            this,
            R.array.target_languages,
            android.R.layout.simple_spinner_item
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        targetLanguageSpinner.adapter = adapter
        targetLanguageSpinner.setSelection(0) // Default to English

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
        val token = prefs.getString("access_token", null) ?: return
        val language = prefs.getString("language", "kannada") ?: "kannada"

        val requestFile = audioFile.asRequestBody("audio/x-wav".toMediaType())
        val filePart = MultipartBody.Part.createFormData("file", audioFile.name, requestFile)

        lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            try {
                val response = RetrofitClient.apiService.transcribeAudio(filePart, language, "Bearer $token")
                val voiceQueryText = response.text
                val timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                if (voiceQueryText.isNotEmpty()) {
                    lastQuery = voiceQueryText
                    val message = Message("Voice Query: $voiceQueryText", timestamp, true)
                    messageList.add(message)
                    messageAdapter.notifyItemInserted(messageList.size - 1)
                    historyRecyclerView.requestLayout()
                    scrollToLatestMessage()
                    getTranslationResponse(voiceQueryText)
                } else {
                    Toast.makeText(this@TranslateActivity, "Voice Query empty", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("TranslateActivity", "Transcription failed: ${e.message}", e)
                Toast.makeText(this@TranslateActivity, "Voice query error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                progressBar.visibility = View.GONE
                audioFile.delete()
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

    private fun getTranslationResponse(input: String) {
        val token = prefs.getString("access_token", null) ?: return
        val srcLang = "kan_Knda" // Hardcoded for now; can be made configurable
        val tgtLang = resources.getStringArray(R.array.target_language_codes)[targetLanguageSpinner.selectedItemPosition]

        val words = input.split("\\s+".toRegex()).filter { it.isNotBlank() }
        val sentences = mutableListOf<String>()
        var currentSentence = mutableListOf<String>()
        var wordCount = 0

        for (word in words) {
            if (wordCount + 1 > 15 && currentSentence.isNotEmpty()) {
                sentences.add(currentSentence.joinToString(" "))
                currentSentence = mutableListOf()
                wordCount = 0
            }
            currentSentence.add(word)
            wordCount++
            if (word.endsWith('.') || word.endsWith('!') || word.endsWith('?')) {
                sentences.add(currentSentence.joinToString(" "))
                currentSentence = mutableListOf()
                wordCount = 0
            }
        }
        if (currentSentence.isNotEmpty()) {
            sentences.add(currentSentence.joinToString(" "))
        }

        val translationRequest = TranslationRequest(sentences, srcLang, tgtLang)

        lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            try {
                val response = RetrofitClient.apiService.translate(translationRequest, "Bearer $token")
                val translatedText = response.translations.joinToString("\n")
                val timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val message = Message("Translation: $translatedText", timestamp, false)
                messageList.add(message)
                messageAdapter.notifyItemInserted(messageList.size - 1)
                historyRecyclerView.requestLayout()
                scrollToLatestMessage()
                textToSpeech(translatedText, message) // Add TTS call
            } catch (e: Exception) {
                Log.e("TranslateActivity", "Translation failed: ${e.message}", e)
                Toast.makeText(this@TranslateActivity, "Translation error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun textToSpeech(text: String, message: Message) {
        if (!prefs.getBoolean("tts_enabled", false)) return
        val token = prefs.getString("access_token", null) ?: return
        val voice = prefs.getString("tts_voice", "Anu speaks with a high pitch at a normal pace in a clear, close-sounding environment. Her neutral tone is captured with excellent audio quality.")
            ?: "Anu speaks with a high pitch at a normal pace in a clear, close-sounding environment. Her neutral tone is captured with excellent audio quality."
        val autoPlay = prefs.getBoolean(AUTO_PLAY_KEY, true)

        lifecycleScope.launch {
            ttsProgressBar.visibility = View.VISIBLE
            try {
                val response = RetrofitClient.apiService.textToSpeech(
                    input = text,
                    voice = voice,
                    model = "ai4bharat/indic-parler-tts",
                    responseFormat = "mp3",
                    speed = 1.0,
                    token = "Bearer $token"
                )
                val audioBytes = response.byteStream().readBytes()
                if (audioBytes.isNotEmpty()) {
                    val audioFile = File(cacheDir, "temp_tts_audio_${System.currentTimeMillis()}.mp3")
                    FileOutputStream(audioFile).use { fos -> fos.write(audioBytes) }
                    if (audioFile.exists() && audioFile.length() > 0) {
                        message.audioFile = audioFile
                        val messageIndex = messageList.indexOf(message)
                        if (messageIndex != -1) {
                            messageAdapter.notifyItemChanged(messageIndex)
                        }
                        if (autoPlay) {
                            playAudio(audioFile)
                        }
                    } else {
                        Toast.makeText(this@TranslateActivity, "Audio file creation failed", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this@TranslateActivity, "TTS returned empty audio", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("TranslateActivity", "TTS failed: ${e.message}", e)
                Toast.makeText(this@TranslateActivity, "TTS error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                ttsProgressBar.visibility = View.GONE
            }
        }
    }

    private fun toggleAudioPlayback(message: Message, button: ImageButton) {
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
    }

    private fun playAudio(audioFile: File) {
        if (!audioFile.exists()) {
            Toast.makeText(this, "Audio file doesn't exist", Toast.LENGTH_SHORT).show()
            return
        }

        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(audioFile.absolutePath)
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
            setOnErrorListener { _, what, extra ->
                Toast.makeText(this@TranslateActivity, "MediaPlayer error: what=$what, extra=$extra", Toast.LENGTH_LONG).show()
                true
            }
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
        }

        override fun getItemCount(): Int = messages.size
    }
}