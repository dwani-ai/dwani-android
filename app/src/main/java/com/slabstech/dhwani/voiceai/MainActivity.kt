package com.slabstech.dhwani.voiceai

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
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
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private val recordAudioPermissionCode = 100
    private var audioRecord: AudioRecord? = null
    private var audioFile: File? = null
    private lateinit var historyRecyclerView: RecyclerView
    private lateinit var audioLevelBar: android.widget.ProgressBar
    private lateinit var progressBar: android.widget.ProgressBar
    private lateinit var pushToTalkFab: FloatingActionButton
    private lateinit var textQueryInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var toolbar: Toolbar
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var currentTheme: Boolean? = null
    private var isRecording = false

    private val viewModel: MainViewModel by viewModel()
    private lateinit var messageAdapter: MessageAdapter

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

        setSupportActionBar(toolbar)

        messageAdapter =
            MessageAdapter(viewModel.messages.value.orEmpty().toMutableList()) { position ->
                showMessageOptionsDialog(position)
            }
        historyRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = messageAdapter
        }

        viewModel.messages.observe(this) { messages ->
            messageAdapter.updateMessages(messages.toMutableList())
            if (toolbar.menu.findItem(R.id.action_auto_scroll)?.isChecked == true) {
                historyRecyclerView.scrollToPosition(messages.size - 1)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                recordAudioPermissionCode,
            )
        }

        pushToTalkFab.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRecording()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    stopRecording()
                    true
                }
                else -> false
            }
        }

        sendButton.setOnClickListener {
            val query = textQueryInput.text.toString().trim()
            if (query.isNotEmpty()) {
                val timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                viewModel.sendTextQuery(query, timestamp)
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
                viewModel.clearMessages()
                true
            }
            R.id.action_repeat -> {
                Toast.makeText(this, "Repeat not implemented yet", Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_share -> {
                val lastMessage = viewModel.messages.value?.lastOrNull()
                if (lastMessage != null) {
                    shareMessage(lastMessage)
                } else {
                    Toast.makeText(this, "No messages to share", Toast.LENGTH_SHORT).show()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun animateFabRecordingStart() {
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.2f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.2f)
        ObjectAnimator.ofPropertyValuesHolder(pushToTalkFab, scaleX, scaleY).apply {
            duration = 200
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            start()
        }
    }

    private fun animateFabRecordingStop() {
        pushToTalkFab.clearAnimation()
        pushToTalkFab.scaleX = 1f
        pushToTalkFab.scaleY = 1f
    }

    private fun showMessageOptionsDialog(position: Int) {
        val message = viewModel.messages.value?.get(position) ?: return
        val options = arrayOf("Copy", "Share")
        AlertDialog.Builder(this)
            .setTitle("Message Options")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> copyMessage(message)
                    1 -> shareMessage(message)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareMessage(message: Message) {
        val shareIntent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "${message.timestamp}: ${message.text}")
            }
        startActivity(Intent.createChooser(shareIntent, "Share message"))
    }

    private fun copyMessage(message: Message) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Message", "${message.timestamp}: ${message.text}")
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Message copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun startRecording() {
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        audioRecord = AudioRecord(AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)
        audioFile = File(cacheDir, "temp_audio.wav")
        val audioBuffer = ByteArray(bufferSize)
        val recordedData = mutableListOf<Byte>()

        isRecording = true
        audioRecord?.startRecording()
        animateFabRecordingStart()

        Thread {
            while (isRecording) {
                val bytesRead = audioRecord?.read(audioBuffer, 0, bufferSize) ?: 0
                if (bytesRead > 0) {
                    recordedData.addAll(audioBuffer.take(bytesRead))
                    val rmsValues = calculateRMS(recordedData.toByteArray())
                    runOnUiThread { audioLevelBar.progress = rmsValues.maxOrNull()?.let { (it * 100).toInt().coerceIn(0, 100) } ?: 0 }
                }
            }
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            if (audioFile != null) {
                writeWavFile(recordedData.toByteArray())
                val timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                sendAudioToApi(timestamp)
            }
        }.start()
    }

    private fun calculateRMS(data: ByteArray): FloatArray {
        val rmsValues = mutableListOf<Float>()
        for (i in 0 until data.size step 2) {
            if (i + 1 < data.size) {
                val sample = (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)
                val rms = (Math.sqrt((sample * sample).toDouble()) / 32768.0).toFloat()
                rmsValues.add(rms)
            }
        }
        return rmsValues.toFloatArray()
    }

    private fun stopRecording() {
        isRecording = false
        animateFabRecordingStop()
    }

    private fun writeWavFile(pcmData: ByteArray) {
        try {
            FileOutputStream(audioFile!!).use { fos ->
                val totalAudioLen = pcmData.size
                val totalDataLen = totalAudioLen + 36
                val channels = 1
                val byteRate = sampleRate * channels * 16 / 8

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
                header.putShort((channels * 16 / 8).toShort())
                header.putShort(16.toShort())
                header.put("data".toByteArray())
                header.putInt(totalAudioLen)

                fos.write(header.array())
                fos.write(pcmData)
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun sendAudioToApi(timestamp: String) {
        progressBar.visibility = View.VISIBLE
        Thread {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val language = prefs.getString("language", "kannada") ?: "kannada"
            val maxRetries = prefs.getString("max_retries", "3")?.toIntOrNull() ?: 3
            val transcriptionApiEndpoint =
                prefs.getString(
                    "transcription_api_endpoint",
                    "https://gaganyatri-asr-indic-server-cpu.hf.space/transcribe/",
                ) ?: "https://gaganyatri-asr-indic-server-cpu.hf.space/transcribe/"

            val client = viewModel.okHttpClient
            val requestBody =
                MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", audioFile!!.name, audioFile!!.asRequestBody("audio/x-wav".toMediaType()))
                    .build()
            val request =
                Request.Builder()
                    .url("$transcriptionApiEndpoint?language=$language")
                    .header("accept", "application/json")
                    .post(requestBody)
                    .build()

            var attempts = 0
            var success = false
            var responseBody: String? = null

            while (attempts < maxRetries && !success) {
                try {
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        responseBody = response.body?.string()
                        success = true
                    }
                    response.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
                if (!success) {
                    attempts++
                    if (attempts < maxRetries) Thread.sleep(2000)
                }
            }

            runOnUiThread {
                progressBar.visibility = View.GONE
                if (success && responseBody != null) {
                    val json = JSONObject(responseBody)
                    val text = json.optString("text", "")
                    if (text.isNotEmpty() && !json.has("error")) {
                        viewModel.addMessage("Voice Query: $text", timestamp, true)
                    } else {
                        viewModel.addMessage("Error: Voice query empty or invalid", timestamp, false)
                    }
                } else {
                    viewModel.addMessage("Error: Failed after $maxRetries retries", timestamp, false)
                }
            }
        }.start()
    }

    class MessageAdapter(
        private val messages: MutableList<Message>,
        private val onLongClick: (Int) -> Unit,
    ) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {
        class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val messageText: TextView = itemView.findViewById(R.id.messageText)
            val timestampText: TextView = itemView.findViewById(R.id.timestampText)
            val messageContainer: LinearLayout = itemView.findViewById(R.id.messageContainer)
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): MessageViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
            return MessageViewHolder(view)
        }

        override fun onBindViewHolder(
            holder: MessageViewHolder,
            position: Int,
        ) {
            val message = messages[position]
            holder.messageText.text = message.text
            holder.timestampText.text = message.timestamp

            val layoutParams = holder.messageContainer.layoutParams as LinearLayout.LayoutParams
            layoutParams.gravity = if (message.isQuery) android.view.Gravity.END else android.view.Gravity.START
            holder.messageContainer.layoutParams = layoutParams

            holder.messageContainer.isActivated = !message.isQuery

            val animation = AnimationUtils.loadAnimation(holder.itemView.context, android.R.anim.fade_in)
            holder.itemView.startAnimation(animation)

            holder.itemView.setOnLongClickListener {
                onLongClick(position)
                true
            }
        }

        override fun getItemCount(): Int = messages.size

        fun updateMessages(newMessages: MutableList<Message>) {
            messages.clear()
            messages.addAll(newMessages)
            notifyDataSetChanged()
        }
    }
}

data class Message(val text: String, val timestamp: String, val isQuery: Boolean)
