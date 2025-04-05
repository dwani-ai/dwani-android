package com.slabstech.dhwani.voiceai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.sqrt

class VoiceDetectionActivity : AppCompatActivity() {

    private val RECORD_AUDIO_PERMISSION_CODE = 101
    private var audioRecord: AudioRecord? = null
    private var audioFile: File? = null
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var audioLevelBar: ProgressBar
    private lateinit var progressBar: ProgressBar
    private lateinit var toolbar: Toolbar
    private var isRecording = false
    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val MIN_ENERGY_THRESHOLD = 0.02f // VAD sensitivity
    private val SILENCE_DURATION_MS = 1000L // 1 second of silence to stop
    private val MIN_RECORDING_DURATION_MS = 1000L
    private var recordingStartTime: Long = 0L
    private var lastVoiceActivityTime: Long = 0L
    private var currentTheme: Boolean? = null
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private var sessionDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val isDarkTheme = prefs.getBoolean("dark_theme", false)
        setTheme(if (isDarkTheme) R.style.Theme_DhwaniVoiceAI_Dark else R.style.Theme_DhwaniVoiceAI_Light)
        currentTheme = isDarkTheme

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_detection)

        checkAuthentication()

        try {
            startButton = findViewById(R.id.startRecordingButton)
            stopButton = findViewById(R.id.stopRecordingButton)
            audioLevelBar = findViewById(R.id.audioLevelBar)
            progressBar = findViewById(R.id.progressBar)
            toolbar = findViewById(R.id.toolbar)
            val bottomNavigation = findViewById<BottomNavigationView>(R.id.bottomNavigation)

            setSupportActionBar(toolbar)

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    RECORD_AUDIO_PERMISSION_CODE
                )
            }

            startButton.setOnClickListener {
                startRecordingWithVAD()
            }

            stopButton.setOnClickListener {
                stopRecording()
            }

            stopButton.isEnabled = false

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
            bottomNavigation.selectedItemId = R.id.nav_answer // Default selection
        } catch (e: Exception) {
            Log.e("VoiceDetectionActivity", "Crash in onCreate: ${e.message}", e)
            Toast.makeText(this, "Initialization failed: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun checkAuthentication() {
        lifecycleScope.launch {
            if (!AuthManager.isAuthenticated(this@VoiceDetectionActivity) || !AuthManager.refreshTokenIfNeeded(this@VoiceDetectionActivity)) {
                Log.d("VoiceDetectionActivity", "Authentication failed, logging out")
                AuthManager.logout(this@VoiceDetectionActivity)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val isDarkTheme = prefs.getBoolean("dark_theme", false)
        if (currentTheme != isDarkTheme) {
            currentTheme = isDarkTheme
            recreate()
            return
        }

        sessionDialog = AlertDialog.Builder(this)
            .setMessage("Checking session...")
            .setCancelable(false)
            .create()
        sessionDialog?.show()

        lifecycleScope.launch {
            val tokenValid = AuthManager.refreshTokenIfNeeded(this@VoiceDetectionActivity)
            Log.d("VoiceDetectionActivity", "onResume: Token valid = $tokenValid")
            if (tokenValid) {
                sessionDialog?.dismiss()
                sessionDialog = null
            } else {
                sessionDialog?.dismiss()
                sessionDialog = null
                AlertDialog.Builder(this@VoiceDetectionActivity)
                    .setTitle("Session Expired")
                    .setMessage("Your session could not be refreshed. Please log in again.")
                    .setPositiveButton("OK") { _, _ ->
                        AuthManager.logout(this@VoiceDetectionActivity)
                    }
                    .setCancelable(false)
                    .show()
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

    private fun startRecordingWithVAD() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        audioFile = File(cacheDir, "voice_detection_audio.wav")
        val audioBuffer = ByteArray(bufferSize)
        val recordedData = mutableListOf<Byte>()

        isRecording = true
        recordingStartTime = System.currentTimeMillis()
        lastVoiceActivityTime = recordingStartTime
        audioRecord?.startRecording()

        startButton.isEnabled = false
        stopButton.isEnabled = true

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                while (isRecording) {
                    val bytesRead = audioRecord?.read(audioBuffer, 0, bufferSize) ?: 0
                    if (bytesRead > 0) {
                        val energy = calculateEnergy(audioBuffer, bytesRead)
                        withContext(Dispatchers.Main) {
                            audioLevelBar.progress = (energy * 100).toInt().coerceIn(0, 100)
                        }

                        if (energy > MIN_ENERGY_THRESHOLD) {
                            lastVoiceActivityTime = System.currentTimeMillis()
                            recordedData.addAll(audioBuffer.take(bytesRead))
                        }

                        if (System.currentTimeMillis() - lastVoiceActivityTime > SILENCE_DURATION_MS &&
                            recordedData.isNotEmpty()) {
                            stopRecording()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@VoiceDetectionActivity, "Recording failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
                val duration = System.currentTimeMillis() - recordingStartTime
                if (duration >= MIN_RECORDING_DURATION_MS && recordedData.isNotEmpty()) {
                    writeWavFile(recordedData.toByteArray())
                    audioFile?.let { sendAudioToApi(it) }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@VoiceDetectionActivity, "Recording too short or no voice detected", Toast.LENGTH_SHORT).show()
                    }
                    audioFile?.delete()
                }
                withContext(Dispatchers.Main) {
                    startButton.isEnabled = true
                    stopButton.isEnabled = false
                    progressBar.visibility = View.GONE
                }
            }
        }
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
        val token = AuthManager.getToken(this) ?: return
        val selectedLanguage = prefs.getString("language", "kannada") ?: "kannada"

        val requestFile = audioFile.asRequestBody("audio/x-wav".toMediaType())
        val filePart = MultipartBody.Part.createFormData("file", audioFile.name, requestFile)

        lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            try {
                val response = RetrofitClient.apiService(this@VoiceDetectionActivity)
                    .transcribeAudio(filePart, selectedLanguage, "Bearer $token")
                val voiceQueryText = response.text
                val timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                if (voiceQueryText.isNotEmpty()) {
                    Toast.makeText(this@VoiceDetectionActivity, "Transcribed: $voiceQueryText [$timestamp]", Toast.LENGTH_LONG).show()
                    // Optionally, redirect to AnswerActivity with the query
                    val intent = Intent(this@VoiceDetectionActivity, AnswerActivity::class.java)
                    intent.putExtra("voice_query", voiceQueryText)
                    startActivity(intent)
                } else {
                    Toast.makeText(this@VoiceDetectionActivity, "No voice detected", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("VoiceDetectionActivity", "Transcription failed: ${e.message}", e)
                Toast.makeText(this@VoiceDetectionActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                progressBar.visibility = View.GONE
                audioFile.delete()
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
        sessionDialog?.dismiss()
        sessionDialog = null
        audioRecord?.release()
        audioRecord = null
        audioFile?.delete()
    }
}