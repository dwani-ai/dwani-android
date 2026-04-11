package com.slabstech.dhwani.voiceai

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.slabstech.dhwani.voiceai.utils.SpeechUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

class VoiceAssistantActivity : AuthenticatedActivity() {

    private var holdToTalkController: HoldToTalkController? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isPipelineBusy = false
    /** Scale-up + breathing pulse while mic is held; cancelled on release. */
    private var pressHoldAnimator: Animator? = null

    private lateinit var toolbar: Toolbar
    private lateinit var bottomNavigation: BottomNavigationView
    private lateinit var statusText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var replyText: TextView
    private lateinit var audioLevelBar: ProgressBar
    private lateinit var pipelineProgressBar: ProgressBar
    private lateinit var ttsProgressBar: ProgressBar
    private lateinit var holdToTalkFab: FloatingActionButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_voice_assistant)
        setupAnswerStyleWindowInsets()

        toolbar = findViewById(R.id.toolbar)
        bottomNavigation = findViewById(R.id.bottomNavigation)
        statusText = findViewById(R.id.statusText)
        transcriptText = findViewById(R.id.transcriptText)
        replyText = findViewById(R.id.replyText)
        audioLevelBar = findViewById(R.id.audioLevelBar)
        pipelineProgressBar = findViewById(R.id.pipelineProgressBar)
        ttsProgressBar = findViewById(R.id.ttsProgressBar)
        holdToTalkFab = findViewById(R.id.holdToTalkFab)

        setSupportActionBar(toolbar)
        NavigationUtils.setupBottomNavigation(this, bottomNavigation, R.id.nav_assistant)

        holdToTalkFab.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startHoldToTalk()
                    if (holdToTalkController != null) {
                        startPressVisuals()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopPressVisuals()
                    stopHoldToTalk()
                    true
                }
                else -> false
            }
        }
    }

    private fun startHoldToTalk() {
        if (holdToTalkController != null || isPipelineBusy) return
        val controller = AudioUtils.startPushToTalkRecording(this, audioLevelBar, {}) { file ->
            holdToTalkController = null
            statusText.setText(R.string.voice_assistant_status_idle)
            if (file == null) {
                Toast.makeText(this, R.string.voice_assistant_too_short, Toast.LENGTH_SHORT).show()
                return@startPushToTalkRecording
            }
            runPipeline(file)
        }
        holdToTalkController = controller
        if (controller != null) {
            statusText.setText(R.string.voice_assistant_status_listening)
        }
    }

    private fun stopHoldToTalk() {
        holdToTalkController?.requestStop()
    }

    private fun startPressVisuals() {
        pressHoldAnimator?.cancel()
        holdToTalkFab.setImageResource(android.R.drawable.ic_media_pause)
        holdToTalkFab.backgroundTintList =
            ContextCompat.getColorStateList(this, android.R.color.holo_red_light)

        val scaleUp = ObjectAnimator.ofPropertyValuesHolder(
            holdToTalkFab,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.12f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.12f)
        ).apply { duration = 180 }

        val pulse = ObjectAnimator.ofPropertyValuesHolder(
            holdToTalkFab,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1.12f, 1.04f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.12f, 1.04f)
        ).apply {
            duration = 650
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }

        pressHoldAnimator = AnimatorSet().apply {
            playSequentially(scaleUp, pulse)
            start()
        }
    }

    private fun stopPressVisuals() {
        pressHoldAnimator?.cancel()
        pressHoldAnimator = null
        holdToTalkFab.setImageResource(R.drawable.ic_mic)
        holdToTalkFab.backgroundTintList =
            ContextCompat.getColorStateList(this, R.color.whatsapp_green)
        val sx = holdToTalkFab.scaleX
        val sy = holdToTalkFab.scaleY
        if (sx != 1f || sy != 1f) {
            ObjectAnimator.ofPropertyValuesHolder(
                holdToTalkFab,
                PropertyValuesHolder.ofFloat(View.SCALE_X, sx, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, sy, 1f)
            ).apply {
                duration = 200
                start()
            }
        }
    }

    private fun runPipeline(audioFile: File) {
        val selectedLanguage = prefs.getString("language", "kannada") ?: "kannada"
        val language = when (selectedLanguage.lowercase()) {
            "hindi", "tamil", "english", "german", "telugu", "malayalam" -> selectedLanguage.lowercase()
            else -> "kannada"
        }
        val languageMap = mapOf(
            "english" to "eng_Latn",
            "hindi" to "hin_Deva",
            "kannada" to "kan_Knda",
            "tamil" to "tam_Taml",
            "german" to "deu_Latn",
            "telugu" to "tel_Telu",
            "malayalam" to "mal_Mlym"
        )
        val langCode = languageMap[selectedLanguage.lowercase()] ?: "kan_Knda"

        val requestFile = audioFile.asRequestBody("audio/mpeg".toMediaType())
        val filePart = MultipartBody.Part.createFormData("file", audioFile.name, requestFile)

        lifecycleScope.launch {
            isPipelineBusy = true
            pipelineProgressBar.visibility = View.VISIBLE
            statusText.setText(R.string.voice_assistant_status_processing)
            try {
                val transcribe = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService(this@VoiceAssistantActivity).transcribeAudio(
                        filePart,
                        language,
                        RetrofitClient.getApiKey()
                    )
                }
                val prompt = transcribe.text.trim()
                transcriptText.text = prompt
                if (prompt.isEmpty()) {
                    Toast.makeText(this@VoiceAssistantActivity, "Nothing recognized", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val request = ChatRequest(prompt, langCode, langCode)
                val chatResponse = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService(this@VoiceAssistantActivity).chat(
                        request,
                        RetrofitClient.getApiKey()
                    )
                }
                val answer = chatResponse.response
                replyText.text = answer

                releaseMediaPlayer()
                statusText.setText(R.string.voice_assistant_status_speaking)
                SpeechUtils.playTtsStandalone(
                    context = this@VoiceAssistantActivity,
                    scope = lifecycleScope,
                    text = answer,
                    forcePlay = true,
                    ttsProgressBarVisibility = { visible ->
                        ttsProgressBar.visibility = if (visible) View.VISIBLE else View.GONE
                    },
                    onPlayerReady = { player -> mediaPlayer = player },
                    onPlaybackComplete = {
                        statusText.setText(R.string.voice_assistant_status_idle)
                    },
                    onTtsFailed = {
                        statusText.setText(R.string.voice_assistant_status_idle)
                    }
                )
            } catch (e: Exception) {
                Log.e("VoiceAssistantActivity", "Pipeline failed: ${e.message}", e)
                Toast.makeText(this@VoiceAssistantActivity, e.message ?: "Error", Toast.LENGTH_LONG).show()
                statusText.setText(R.string.voice_assistant_status_idle)
            } finally {
                isPipelineBusy = false
                pipelineProgressBar.visibility = View.GONE
                audioFile.delete()
            }
        }
    }

    private fun releaseMediaPlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPressVisuals()
        holdToTalkController?.requestStop()
        holdToTalkController = null
        releaseMediaPlayer()
    }
}
