package com.slabstech.dhwani.voiceai

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder.AudioSource
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

class MainActivity : AppCompatActivity() {

    private val RECORD_AUDIO_PERMISSION_CODE = 100
    private var audioRecord: AudioRecord? = null
    private var audioFile: File? = null
    private lateinit var historyRecyclerView: RecyclerView
    private lateinit var audioLevelBar: ProgressBar
    private lateinit var progressBar: ProgressBar
    private lateinit var pushToTalkButton: Button
    private lateinit var clearButton: Button
    private lateinit var repeatButton: Button
    private lateinit var autoScrollToggle: CheckBox
    private lateinit var languageSpinner: Spinner
    private var isRecording = false
    private val SAMPLE_RATE = 16000 // 16 kHz
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val MAX_RETRIES = 3
    private val RETRY_DELAY_MS = 2000L // 2 seconds
    private val messageList = mutableListOf<Message>()
    private lateinit var messageAdapter: MessageAdapter
    private var lastTranscription: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        historyRecyclerView = findViewById(R.id.historyRecyclerView)
        audioLevelBar = findViewById(R.id.audioLevelBar)
        progressBar = findViewById(R.id.progressBar)
        pushToTalkButton = findViewById(R.id.pushToTalkButton)
        clearButton = findViewById(R.id.clearButton)
        repeatButton = findViewById(R.id.repeatButton)
        autoScrollToggle = findViewById(R.id.autoScrollToggle)
        languageSpinner = findViewById(R.id.languageSpinner)

        // Setup RecyclerView
        messageAdapter = MessageAdapter(messageList)
        historyRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = messageAdapter
        }

        // Language Spinner
        val languages = arrayOf("kannada", "hindi", "tamil")
        languageSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languages)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                RECORD_AUDIO_PERMISSION_CODE
            )
        }

        pushToTalkButton.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startRecording()
                    pushToTalkButton.text = "Recording..."
                }
                android.view.MotionEvent.ACTION_UP -> {
                    stopRecording()
                    pushToTalkButton.text = "Push to Talk"
                    audioLevelBar.progress = 0
                }
            }
            true
        }

        clearButton.setOnClickListener {
            messageList.clear()
            lastTranscription = null
            messageAdapter.notifyDataSetChanged()
        }

        repeatButton.setOnClickListener {
            lastTranscription?.let { getChatResponse(it) }
                ?: Toast.makeText(this, "No previous transcription to repeat", Toast.LENGTH_SHORT).show()
        }
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
                writeWavFile(recordedData.toByteArray())
                if (audioFile != null && audioFile!!.exists()) {
                    sendAudioToApi(audioFile)
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

        val language = languageSpinner.selectedItem.toString()
        val request = Request.Builder()
            .url("https://gaganyatri-asr-indic-server-cpu.hf.space/transcribe/?language=$language")
            .header("accept", "application/json")
            .post(requestBody)
            .build()

        Thread {
            var attempts = 0
            var success = false
            var responseBody: String? = null

            while (attempts < MAX_RETRIES && !success) {
                try {
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        responseBody = response.body?.string()
                        success = true
                    } else {
                        attempts++
                        if (attempts < MAX_RETRIES) Thread.sleep(RETRY_DELAY_MS)
                        runOnUiThread {
                            Toast.makeText(this, "Transcription API failed: ${response.code}, retrying ($attempts/$MAX_RETRIES)", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                    attempts++
                    if (attempts < MAX_RETRIES) Thread.sleep(RETRY_DELAY_MS)
                    runOnUiThread {
                        Toast.makeText(this, "Network error: ${e.message}, retrying ($attempts/$MAX_RETRIES)", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            responseBody?.let {
                val json = JSONObject(it)
                val transcribedText = json.getString("text")
                val timestamp = SimpleDateFormat("MM/dd/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
                lastTranscription = transcribedText
                val message = Message("Transcription [$timestamp]: $transcribedText", true)
                runOnUiThread {
                    messageList.add(message)
                    messageAdapter.notifyItemInserted(messageList.size - 1)
                    if (autoScrollToggle.isChecked) {
                        historyRecyclerView.scrollToPosition(messageList.size - 1)
                    }
                    progressBar.visibility = View.GONE
                }
                getChatResponse(transcribedText)
            } ?: runOnUiThread {
                Toast.makeText(this, "Transcription failed after $MAX_RETRIES retries", Toast.LENGTH_SHORT).show()
                progressBar.visibility = View.GONE
            }
        }.start()
    }

    private fun getChatResponse(prompt: String) {
        runOnUiThread { progressBar.visibility = View.VISIBLE }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val jsonMediaType = "application/json".toMediaType()
        val requestBody = JSONObject().put("prompt", prompt).toString().toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("https://gaganyatri-llm-indic-server-cpu.hf.space/chat")
            .header("accept", "application/json")
            .post(requestBody)
            .build()

        Thread {
            var attempts = 0
            var success = false
            var responseBody: String? = null

            while (attempts < MAX_RETRIES && !success) {
                try {
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        responseBody = response.body?.string()
                        success = true
                    } else {
                        attempts++
                        if (attempts < MAX_RETRIES) Thread.sleep(RETRY_DELAY_MS)
                        runOnUiThread {
                            Toast.makeText(this, "Chat API failed: ${response.code}, retrying ($attempts/$MAX_RETRIES)", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                    attempts++
                    if (attempts < MAX_RETRIES) Thread.sleep(RETRY_DELAY_MS)
                    runOnUiThread {
                        Toast.makeText(this, "Network error: ${e.message}, retrying ($attempts/$MAX_RETRIES)", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            responseBody?.let {
                val json = JSONObject(it)
                val chatResponse = json.getString("response")
                val timestamp = SimpleDateFormat("MM/dd/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
                val message = Message("Response [$timestamp]: $chatResponse", false)
                runOnUiThread {
                    messageList.add(message)
                    messageAdapter.notifyItemInserted(messageList.size - 1)
                    if (autoScrollToggle.isChecked) {
                        historyRecyclerView.scrollToPosition(messageList.size - 1)
                    }
                    progressBar.visibility = View.GONE
                }
            } ?: runOnUiThread {
                Toast.makeText(this, "Chat failed after $MAX_RETRIES retries", Toast.LENGTH_SHORT).show()
                progressBar.visibility = View.GONE
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
    }
}

data class Message(val text: String, val isTranscription: Boolean)

class MessageAdapter(private val messages: List<Message>) :
    RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val messageText: TextView = itemView.findViewById(R.id.messageText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        holder.messageText.text = message.text
        // Style based on type
        holder.messageText.setTextColor(
            if (message.isTranscription) 0xFF2196F3.toInt() else 0xFF4CAF50.toInt() // Blue for transcription, green for response
        )
        holder.messageText.gravity = if (message.isTranscription) android.view.Gravity.START else android.view.Gravity.END
    }

    override fun getItemCount(): Int = messages.size
}