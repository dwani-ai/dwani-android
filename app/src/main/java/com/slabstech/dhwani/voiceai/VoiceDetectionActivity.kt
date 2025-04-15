package com.slabstech.dhwani.voiceai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import android.widget.ToggleButton
import androidx.appcompat.app.AlertDialog
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
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class VoiceDetectionActivity : AuthenticatedActivity() {

    private val RECORD_AUDIO_PERMISSION_CODE = 101
    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val MIN_ENERGY_THRESHOLD = 0.04f
    private val PAUSE_DURATION_MS = 1000L
    private val GCM_TAG_LENGTH = 16
    private val GCM_NONCE_LENGTH = 12

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("VoiceDetectionActivity", "onCreate called")
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

        NavigationUtils.setupBottomNavigation(this, bottomNavigation, R.id.nav_voice)
    }

    override fun onResume() {
        super.onResume()
        Log.d("VoiceDetectionActivity", "onResume called")
    }

    private fun startRecording() {
        AudioUtils.startContinuousRecording(this, audioLevelBar, recordingIndicator) { audioData ->
            processAudioChunk(audioData)
        }
    }

    private fun stopRecording() {
        isRecording = false
        Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show()
    }

    private fun processAudioChunk(audioData: ByteArray) {
        lifecycleScope.launch(Dispatchers.IO) {
            val chunkFile = File(cacheDir, "voice_chunk_${System.currentTimeMillis()}.wav")
            AudioUtils.writeWavFile(audioData, chunkFile)

            if (chunkFile.length() > 44) {
                sendAudioToApi(chunkFile)
            } else {
                Log.d("VoiceDetectionActivity", "Skipping empty file: ${chunkFile.length()} bytes")
            }
        }
    }

    private fun sendAudioToApi(audioFile: File) {
        val token = AuthManager.getToken(this) ?: run {
            runOnUiThread {
                Toast.makeText(this, "Please log in to use voice detection.", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val language = "kannada"
        val voiceDescription = "Anu speaks with a high pitch at a normal pace in a clear environment."

        val audioBytes = audioFile.readBytes()
        val encryptedAudio = RetrofitClient.encryptAudio(audioBytes, sessionKey)
        val encryptedFile = File(cacheDir, "encrypted_${audioFile.name}")
        FileOutputStream(encryptedFile).use { it.write(encryptedAudio) }

        val encryptedLanguage = RetrofitClient.encryptText(language, sessionKey)
        val encryptedVoice = RetrofitClient.encryptText(voiceDescription, sessionKey)

        val requestFile = encryptedFile.asRequestBody("application/octet-stream".toMediaType())
        val filePart = MultipartBody.Part.createFormData("file", encryptedFile.name, requestFile)
        val voicePart = encryptedVoice.toRequestBody("text/plain".toMediaType())

        lifecycleScope.launch {
            withContext(Dispatchers.Main) {
                recordingIndicator.visibility = View.VISIBLE
                recordingIndicator.startAnimation(clockTickAnimation)
            }
            try {
                val cleanSessionKey = Base64.encodeToString(sessionKey, Base64.NO_WRAP)
                Log.d("VoiceDetectionActivity", "Sending API request with session key: $cleanSessionKey")
                val response = withTimeout(30000L) {
                    RetrofitClient.apiService(this@VoiceDetectionActivity).speechToSpeech(
                        language = encryptedLanguage,
                        file = filePart,
                        voice = voicePart,
                        token = "Bearer $token",
                        sessionKey = cleanSessionKey
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
                    } else {
                        Log.e("VoiceDetectionActivity", "Empty response from API")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@VoiceDetectionActivity, "No audio response received from server.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    val errorBody = response.errorBody()?.string() ?: "Unknown error"
                    Log.e("VoiceDetectionActivity", "API error: ${response.code()} - $errorBody")
                    withContext(Dispatchers.Main) {
                        when (response.code()) {
                            401 -> {
                                Toast.makeText(this@VoiceDetectionActivity, "Authentication failed. Please log in again.", Toast.LENGTH_SHORT).show()
                                AuthManager.logout(this@VoiceDetectionActivity)
                            }
                            400 -> Toast.makeText(this@VoiceDetectionActivity, "Invalid audio input. Please try again.", Toast.LENGTH_SHORT).show()
                            in 500..599 -> Toast.makeText(this@VoiceDetectionActivity, "Server error. Please try again later.", Toast.LENGTH_SHORT).show()
                            else -> Toast.makeText(this@VoiceDetectionActivity, "Speech-to-speech failed: Error ${response.code()}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.e("VoiceDetectionActivity", "Speech-to-speech failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@VoiceDetectionActivity, "Network timeout. Please check your connection.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("VoiceDetectionActivity", "Speech-to-speech failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@VoiceDetectionActivity, "An error occurred: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                audioFile.delete()
                encryptedFile.delete()
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
                setOnErrorListener { _, what, extra ->
                    Log.e("VoiceDetectionActivity", "MediaPlayer error: $what, $extra")
                    runOnUiThread {
                        Toast.makeText(this@VoiceDetectionActivity, "Playback failed. Please try again.", Toast.LENGTH_SHORT).show()
                    }
                    cleanupMediaPlayer()
                    true
                }
            }
        } catch (e: Exception) {
            Log.e("VoiceDetectionActivity", "Playback failed: ${e.message}", e)
            cleanupMediaPlayer()
            runOnUiThread {
                Toast.makeText(this@VoiceDetectionActivity, "Unable to play audio: ${e.message}", Toast.LENGTH_SHORT).show()
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
        Log.d("VoiceDetectionActivity", "onDestroy called")
        audioRecord?.release()
        audioRecord = null
        cleanupMediaPlayer()
        recordingIndicator.clearAnimation()
        playbackIndicator.clearAnimation()
        sessionDialog?.dismiss()
        sessionDialog = null
    }
}