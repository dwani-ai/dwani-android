package com.slabstech.dhwani.voiceai

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder.AudioSource
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private val RECORD_AUDIO_PERMISSION_CODE = 100
    private var audioRecord: AudioRecord? = null
    private var audioFile: File? = null
    private lateinit var transcriptionText: TextView
    private lateinit var transcriptionScrollView: ScrollView
    private lateinit var responseText: TextView
    private lateinit var responseScrollView: ScrollView
    private var isRecording = false
    private val SAMPLE_RATE = 16000 // 16 kHz
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val pushToTalkButton = findViewById<Button>(R.id.pushToTalkButton)
        transcriptionText = findViewById(R.id.transcriptionText)
        transcriptionScrollView = findViewById(R.id.transcriptionScrollView)
        responseText = findViewById(R.id.responseText)
        responseScrollView = findViewById(R.id.responseScrollView)

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
                }
            }
            true
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

    private fun stopRecording() {
        isRecording = false
    }

    private fun writeWavFile(pcmData: ByteArray) {
        audioFile?.let { file ->
            try {
                FileOutputStream(file).use { fos ->
                    val totalAudioLen = pcmData.size
                    val totalDataLen = totalAudioLen + 36
                    val channels = 1 // Mono
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

        // Configure OkHttpClient with longer timeouts
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS) // Wait up to 30s to connect
            .readTimeout(30, TimeUnit.SECONDS)    // Wait up to 30s for response
            .writeTimeout(30, TimeUnit.SECONDS)   // Wait up to 30s to send data
            .build()

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", audioFile.name,
                audioFile.asRequestBody("audio/x-wav".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("https://gaganyatri-asr-indic-server-cpu.hf.space/transcribe/?language=kannada")
            .header("accept", "application/json")
            .post(requestBody)
            .build()

        Thread {
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    responseBody?.let {
                        val json = JSONObject(it)
                        val transcribedText = json.getString("text")
                        runOnUiThread {
                            val currentTranscription = transcriptionText.text.toString()
                            transcriptionText.text = if (currentTranscription == "Transcription will appear here") {
                                transcribedText
                            } else {
                                "$currentTranscription\n$transcribedText"
                            }
                            transcriptionScrollView.post { transcriptionScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                        }
                        getChatResponse(transcribedText)
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "Transcription API failed: ${response.code}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Network error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun getChatResponse(prompt: String) {
        // Configure OkHttpClient with longer timeouts
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS) // Wait up to 30s to connect
            .readTimeout(30, TimeUnit.SECONDS)    // Wait up to 30s for response
            .writeTimeout(30, TimeUnit.SECONDS)   // Wait up to 30s to send data
            .build()

        val jsonMediaType = "application/json".toMediaType()
        val requestBody = JSONObject().put("prompt", prompt).toString().toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("https://gaganyatri-llm-indic-server-cpu.hf.space/chat")
            .header("accept", "application/json")
            .post(requestBody)
            .build()

        Thread {
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    responseBody?.let {
                        val json = JSONObject(it)
                        val chatResponse = json.getString("response")
                        runOnUiThread {
                            val currentResponse = responseText.text.toString()
                            responseText.text = if (currentResponse == "Responses will appear here") {
                                chatResponse
                            } else {
                                "$currentResponse\n$chatResponse"
                            }
                            responseScrollView.post { responseScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                        }
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "Chat API failed: ${response.code}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Network error: ${e.message}", Toast.LENGTH_SHORT).show()
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
    }
}