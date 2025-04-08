package com.slabstech.dhwani.voiceai

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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

class VoiceDetectionActivity : AppCompatActivity() {

    private val RECORD_AUDIO_PERMISSION_CODE = 101
    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val MIN_ENERGY_THRESHOLD = 0.04f
    private val PAUSE_DURATION_MS = 2000L

    private lateinit var toggleRecordButton: ToggleButton
    private lateinit var audioLevelBar: ProgressBar
    private lateinit var recordingIndicator: ImageView
    private lateinit var playbackIndicator: ImageView
    private lateinit var toolbar: Toolbar
    private lateinit var bottomNavigation: BottomNavigationView
    private lateinit var pulseAnimation: Animation
    private lateinit var clockTickAnimation: Animation

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var playbackActive = false
    private var mediaPlayer: MediaPlayer? = null
    private var latestAudioFile: File? = null

    private val speechToSpeechApi: SpeechToSpeechApi by lazy {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
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

        toggleRecordButton = findViewById(R.id.toggleRecordButton)
        audioLevelBar = findViewById(R.id.audioLevelBar)
        recordingIndicator = findViewById(R.id.recordingIndicator)
        playbackIndicator = findViewById(R.id.playbackIndicator)
        toolbar = findViewById(R.id.toolbar)
        bottomNavigation = findViewById(R.id.bottomNavigation)
        pulseAnimation = AnimationUtils.loadAnimation(this, R.anim.pulse)
        clockTickAnimation = AnimationUtils.loadAnimation(this, R.anim.clock_tick)

        setSupportActionBar(toolbar)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                RECORD_AUDIO_PERMISSION_CODE
            )
        }

        toggleRecordButton.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) startRecording() else stopRecording()
        }

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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted
            } else {
                Toast.makeText(this, "Audio permission is required", Toast.LENGTH_LONG).show()
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
            var hasVoiceData = false

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
                    recordedData.clear()
                    lastVoiceTime = System.currentTimeMillis()
                    hasVoiceData = false
                    continue
                }

                val bytesRead = audioRecord?.read(audioBuffer, 0, bufferSize) ?: 0
                if (bytesRead > 0) {
                    val energy = calculateEnergy(audioBuffer, bytesRead)
                    val currentTime = System.currentTimeMillis()

                    withContext(Dispatchers.Main) {
                        audioLevelBar.progress = (energy * 100).toInt().coerceIn(0, 100)
                    }

                    if (energy > MIN_ENERGY_THRESHOLD) {
                        recordedData.addAll(audioBuffer.take(bytesRead))
                        lastVoiceTime = currentTime
                        hasVoiceData = true
                    } else if (hasVoiceData && (currentTime - lastVoiceTime) >= PAUSE_DURATION_MS) {
                        Log.d("VoiceDetection", "2-second pause detected, processing audio, size: ${recordedData.size}")
                        val audioData = recordedData.toByteArray()
                        processAudioChunk(audioData)
                        recordedData.clear()
                        hasVoiceData = false
                        lastVoiceTime = currentTime
                    } else if (energy <= MIN_ENERGY_THRESHOLD && recordedData.isNotEmpty()) {
                        recordedData.addAll(audioBuffer.take(bytesRead))
                    }
                }
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

    private fun calculateEnergy(buffer: ByteArray, bytesRead: Int): Float {
        var sum = 0L
        for (i in 0 until bytesRead step 2) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            sum += sample * sample
        }
        val meanSquare = sum / (bytesRead / 2)
        return sqrt(meanSquare.toDouble()).toFloat() / 32768.0f
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
                val response = withTimeout(1000000L) {
                    speechToSpeechApi.speechToSpeech(
                        language = language,
                        file = filePart,
                        voice = voicePart,
                        token = "Bearer $token"
                    )
                }

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
            } catch (e: TimeoutCancellationException) {
                Log.e("VoiceDetection", "API call timed out: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@VoiceDetectionActivity, "Network timeout", Toast.LENGTH_SHORT).show()
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
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.stop()
            }
            it.reset()
        } ?: run {
            mediaPlayer = MediaPlayer()
        }

        latestAudioFile?.takeIf { it.exists() }?.delete()
        latestAudioFile = audioFile

        try {
            mediaPlayer?.apply {
                setDataSource(audioFile.absolutePath)
                prepare()
                start()
                playbackActive = true

                runOnUiThread {
                    playbackIndicator.visibility = View.VISIBLE
                    playbackIndicator.startAnimation(pulseAnimation)
                }

                setOnCompletionListener {
                    cleanupMediaPlayer()
                }
                setOnErrorListener { mp, what, extra ->
                    Log.e("VoiceDetection", "MediaPlayer error: $what, $extra")
                    runOnUiThread {
                        Toast.makeText(this@VoiceDetectionActivity, "Playback error", Toast.LENGTH_SHORT).show()
                    }
                    cleanupMediaPlayer()
                    true
                }
            }
        } catch (e: Exception) {
            Log.e("VoiceDetection", "Playback failed: ${e.message}", e)
            cleanupMediaPlayer()
            runOnUiThread {
                Toast.makeText(this@VoiceDetectionActivity, "Playback failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun cleanupMediaPlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
        latestAudioFile?.takeIf { it.exists() }?.delete()
        playbackActive = false
        runOnUiThread {
            playbackIndicator.clearAnimation()
            playbackIndicator.visibility = View.INVISIBLE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioRecord?.release()
        audioRecord = null
        cleanupMediaPlayer()
        recordingIndicator.clearAnimation()
        playbackIndicator.clearAnimation()
    }
}