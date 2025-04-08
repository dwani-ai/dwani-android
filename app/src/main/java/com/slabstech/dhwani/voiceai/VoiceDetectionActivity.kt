package com.slabstech.dhwani.voiceai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import android.widget.ToggleButton
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.TimeUnit

class VoiceDetectionActivity : AppCompatActivity() {

    // Constants for audio configuration
    private val RECORD_AUDIO_PERMISSION_CODE = 101
    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val PAUSE_THRESHOLD_MS = 1000L // 1-second pause
    private val VAD_THRESHOLD = 0.5f // Silero VAD probability threshold

    // UI elements
    private lateinit var toggleRecordButton: ToggleButton
    private lateinit var audioLevelBar: ProgressBar
    private lateinit var recordingIndicator: ImageView
    private lateinit var playbackIndicator: ImageView
    private lateinit var toolbar: Toolbar
    private lateinit var bottomNavigation: BottomNavigationView
    private lateinit var pulseAnimation: Animation
    private lateinit var clockTickAnimation: Animation

    // State variables
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var playbackActive = false
    private var mediaPlayer: MediaPlayer? = null
    private var latestAudioFile: File? = null

    // Silero VAD setup
    private lateinit var ortEnvironment: OrtEnvironment
    private lateinit var ortSession: OrtSession

    // Retrofit API setup
    private val speechToSpeechApi: SpeechToSpeechApi by lazy {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl("https://slabstech-dhwani-internal-api-server.hf.space/")
            .client(okHttpClient)
            .build()
            .create(SpeechToSpeechApi::class.java)
    }

    interface SpeechToSpeechApi {
        @Multipart
        @POST("v1/speech_to_speech")
        suspend fun speechToSpeech(
            @Query("language") language: String,
            @Part file: MultipartBody.Part,
            @Part("voice") voice: RequestBody,
            @Header("Authorization") token: String
        ): retrofit2.Response<okhttp3.ResponseBody>
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_detection)

        // Initialize UI elements
        toggleRecordButton = findViewById(R.id.toggleRecordButton)
        audioLevelBar = findViewById(R.id.audioLevelBar)
        recordingIndicator = findViewById(R.id.recordingIndicator)
        playbackIndicator = findViewById(R.id.playbackIndicator)
        toolbar = findViewById(R.id.toolbar)
        bottomNavigation = findViewById(R.id.bottomNavigation)
        pulseAnimation = AnimationUtils.loadAnimation(this, R.anim.pulse)
        clockTickAnimation = AnimationUtils.loadAnimation(this, R.anim.clock_tick)

        // Set up the Toolbar
        setSupportActionBar(toolbar)

        // Initialize Silero VAD
        ortEnvironment = OrtEnvironment.getEnvironment()
        ortSession = ortEnvironment.createSession(
            assets.open("silero_vad.onnx").readBytes(), // Place model in assets folder
            OrtSession.SessionOptions()
        )

        // Check and request recording permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                RECORD_AUDIO_PERMISSION_CODE
            )
        }

        // Toggle button listener
        toggleRecordButton.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) startRecording() else stopRecording()
        }

        // Bottom Navigation listener
        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_answer -> {
                    AlertDialog.Builder(this)
                        .setMessage("Switch to Answers?")
                        .setPositiveButton("Yes") { _, _ ->
                            startActivity(Intent(this, AnswerActivity::class.java))
                        }
                        .setNegativeButton("No", null)
                        .show()
                    false
                }
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
        bottomNavigation.selectedItemId = R.id.nav_voice
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
            R.id.action_logout -> {
                AuthManager.logout(this)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
            toggleRecordButton.isChecked = false
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * 4
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("VoiceDetection", "AudioRecord initialization failed")
            Toast.makeText(this, "Failed to initialize recording", Toast.LENGTH_SHORT).show()
            toggleRecordButton.isChecked = false
            return
        }

        isRecording = true
        audioRecord?.startRecording()
        Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
        Log.d("VoiceDetection", "Recording started with buffer size: $bufferSize")

        lifecycleScope.launch(Dispatchers.IO) {
            val audioBuffer = ByteArray(bufferSize)
            val recordedData = mutableListOf<Byte>()
            var lastVoiceTime = System.currentTimeMillis()

            withContext(Dispatchers.Main) {
                recordingIndicator.visibility = View.VISIBLE
                recordingIndicator.startAnimation(clockTickAnimation)
            }

            while (isRecording) {
                if (playbackActive) {
                    audioRecord?.stop()
                    withContext(Dispatchers.Main) {
                        recordingIndicator.clearAnimation()
                        recordingIndicator.visibility = View.INVISIBLE
                    }
                    while (playbackActive) {
                        Thread.sleep(100)
                    }
                    if (isRecording) {
                        audioRecord?.startRecording()
                        withContext(Dispatchers.Main) {
                            recordingIndicator.visibility = View.VISIBLE
                            recordingIndicator.startAnimation(clockTickAnimation)
                        }
                    }
                    lastVoiceTime = System.currentTimeMillis()
                    recordedData.clear()
                    continue
                }

                val bytesRead = audioRecord?.read(audioBuffer, 0, bufferSize) ?: 0
                if (bytesRead > 0) {
                    val currentTime = System.currentTimeMillis()
                    val hasVoice = detectVoiceWithSilero(audioBuffer, bytesRead)

                    if (hasVoice) {
                        recordedData.addAll(audioBuffer.take(bytesRead))
                        lastVoiceTime = currentTime
                    } else if (recordedData.isNotEmpty() &&
                        (currentTime - lastVoiceTime) >= PAUSE_THRESHOLD_MS) {
                        val audioChunk = recordedData.toByteArray()
                        Log.d("VoiceDetection", "Pause detected, processing chunk of size: ${audioChunk.size}")
                        processAudioChunk(audioChunk)
                        recordedData.clear()
                        lastVoiceTime = currentTime
                    }

                    withContext(Dispatchers.Main) {
                        audioLevelBar.progress = if (hasVoice) 75 else 25
                    }
                }
            }

            if (recordedData.isNotEmpty()) {
                processAudioChunk(recordedData.toByteArray())
            }

            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            withContext(Dispatchers.Main) {
                toggleRecordButton.isChecked = false
                recordingIndicator.clearAnimation()
                recordingIndicator.visibility = View.INVISIBLE
            }
        }
    }

    private fun stopRecording() {
        isRecording = false
        Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show()
    }

    private fun detectVoiceWithSilero(audioData: ByteArray, bytesRead: Int): Boolean {
        val floatArray = FloatArray(bytesRead / 2)
        for (i in floatArray.indices) {
            val sample = (audioData[i * 2].toInt() and 0xFF) or (audioData[i * 2 + 1].toInt() shl 8)
            floatArray[i] = sample / 32768.0f
        }

        if (floatArray.size < 1536) return false

        val inputTensor = OnnxTensor.createTensor(
            ortEnvironment,
            FloatBuffer.wrap(floatArray.take(1536).toFloatArray()),
            longArrayOf(1, 1536)
        )

        val outputs = ortSession.run(mapOf("input" to inputTensor))
        val prob = outputs[0].value as Array<FloatArray>
        val voiceProb = prob[0][0]

        Log.d("VoiceDetection", "Voice probability: $voiceProb")
        return voiceProb > VAD_THRESHOLD
    }

    private fun writeWavFile(pcmData: ByteArray, outputFile: File) {
        FileOutputStream(outputFile).use { fos ->
            val totalAudioLen = pcmData.size
            val totalDataLen = totalAudioLen + 36
            val channels = 1
            val sampleRate = SAMPLE_RATE
            val bitsPerSample = 16
            val byteRate = sampleRate * channels * bitsPerSample / 8

            val header = ByteBuffer.allocate(44).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                put("RIFF".toByteArray())
                putInt(totalDataLen)
                put("WAVE".toByteArray())
                put("fmt ".toByteArray())
                putInt(16)
                putShort(1.toShort())
                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(byteRate)
                putShort((channels * bitsPerSample / 8).toShort())
                putShort(bitsPerSample.toShort())
                put("data".toByteArray())
                putInt(totalAudioLen)
            }

            fos.write(header.array())
            fos.write(pcmData)
        }
        Log.d("VoiceDetection", "WAV file written: ${outputFile.length()} bytes")
    }

    private fun processAudioChunk(audioData: ByteArray) {
        lifecycleScope.launch(Dispatchers.IO) {
            val chunkFile = File(cacheDir, "voice_chunk_${System.currentTimeMillis()}.wav")
            writeWavFile(audioData, chunkFile)

            if (chunkFile.length() > 44) {
                sendAudioToApi(chunkFile)
            } else {
                Log.d("VoiceDetection", "Skipping empty file: ${chunkFile.length()} bytes")
            }
        }
    }

    private fun sendAudioToApi(audioFile: File) {
        val token = "YOUR_AUTH_TOKEN" // Replace with actual token retrieval logic
        val language = "kannada"
        val voiceDescription = "Anu speaks with a high pitch at a normal pace in a clear environment."

        val requestFile = audioFile.asRequestBody("audio/x-wav".toMediaType())
        val filePart = MultipartBody.Part.createFormData("file", audioFile.name, requestFile)
        val voicePart = voiceDescription.toRequestBody("text/plain".toMediaType())

        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                recordingIndicator.visibility = View.VISIBLE
                recordingIndicator.startAnimation(clockTickAnimation)
            }
            try {
                val response = speechToSpeechApi.speechToSpeech(
                    language = language,
                    file = filePart,
                    voice = voicePart,
                    token = "Bearer $token"
                )

                if (response.isSuccessful) {
                    val audioBytes = response.body()?.bytes()
                    if (audioBytes != null && audioBytes.isNotEmpty()) {
                        val outputFile = File(cacheDir, "speech_output_${System.currentTimeMillis()}.mp3")
                        FileOutputStream(outputFile).use { it.write(audioBytes) }
                        withContext(Dispatchers.Main) {
                            playAudio(outputFile)
                            Toast.makeText(this@VoiceDetectionActivity, "Response played", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Log.e("VoiceDetection", "API error: ${response.code()} - ${response.errorBody()?.string()}")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@VoiceDetectionActivity, "API error: ${response.code()}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("VoiceDetection", "API call failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@VoiceDetectionActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                audioFile.delete()
                withContext(Dispatchers.Main) {
                    recordingIndicator.clearAnimation()
                    recordingIndicator.visibility = View.INVISIBLE
                }
            }
        }
    }

    private fun playAudio(audioFile: File) {
        mediaPlayer?.release()
        latestAudioFile?.delete()
        latestAudioFile = audioFile

        playbackActive = true
        mediaPlayer = MediaPlayer().apply {
            setDataSource(audioFile.absolutePath)
            prepare()
            start()
            playbackIndicator.visibility = View.VISIBLE
            playbackIndicator.startAnimation(pulseAnimation)
            setOnCompletionListener {
                it.release()
                mediaPlayer = null
                latestAudioFile?.delete()
                playbackActive = false
                playbackIndicator.clearAnimation()
                playbackIndicator.visibility = View.INVISIBLE
            }
            setOnErrorListener { mp, what, extra ->
                Log.e("VoiceDetection", "MediaPlayer error: $what, $extra")
                Toast.makeText(this@VoiceDetectionActivity, "Playback error", Toast.LENGTH_SHORT).show()
                mp.release()
                mediaPlayer = null
                latestAudioFile?.delete()
                playbackActive = false
                playbackIndicator.clearAnimation()
                playbackIndicator.visibility = View.INVISIBLE
                true
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioRecord?.release()
        mediaPlayer?.release()
        latestAudioFile?.delete()
        ortSession.close()
        ortEnvironment.close()
        recordingIndicator.clearAnimation()
        playbackIndicator.clearAnimation()
    }
}