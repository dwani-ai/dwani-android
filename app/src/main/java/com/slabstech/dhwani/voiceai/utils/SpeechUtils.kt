package com.slabstech.dhwani.voiceai.utils

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.widget.ImageButton
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.slabstech.dhwani.voiceai.Message
import com.slabstech.dhwani.voiceai.MessageAdapter
import com.slabstech.dhwani.voiceai.R
import com.slabstech.dhwani.voiceai.RetrofitClient
import com.slabstech.dhwani.voiceai.TranslationRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException

object SpeechUtils {
    private const val AUTO_PLAY_KEY = "auto_play_tts"
    private const val MAX_TTS_INPUT_LENGTH = 1000 // Matches server-side limit in main.py

    // Language codes from resources and AnswerActivity
    private val europeanLanguages = setOf(
        "eng_Latn", // English
        "deu_Latn", // German
    )

    private val indianLanguages = setOf(
        "hin_Deva",  // Hindi
        "kan_Knda",  // Kannada
        "tam_Taml",  // Tamil
        "mal_Mlym",  // Malayalam
        "tel_Telu"   // Telugu
    )

    fun textToSpeech(
        context: Context,
        scope: LifecycleCoroutineScope,
        text: String, // Plain text
        message: Message,
        recyclerView: RecyclerView,
        adapter: MessageAdapter,
        ttsProgressBarVisibility: (Boolean) -> Unit,
        srcLang: String
    ) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean("tts_enabled", false)) {
            Log.d("SpeechUtils", "TTS disabled in preferences")
            return
        }

        // Truncate text to avoid exceeding server limits
        val truncatedText = if (text.length > MAX_TTS_INPUT_LENGTH) {
            Log.w("SpeechUtils", "Input text too long (${text.length} chars), truncating")
            text.substring(0, MAX_TTS_INPUT_LENGTH)
        } else {
            text
        }

        val autoPlay = prefs.getBoolean(AUTO_PLAY_KEY, true)

        scope.launch {
            ttsProgressBarVisibility(true)
            val selectedLanguage = prefs.getString("language", "kannada") ?: "kannada"
            val supportedLanguage = when (selectedLanguage.lowercase()) {
                "hindi" -> "hindi"
                "tamil" -> "tamil"
                "english" -> "english"
                "german" -> "german"
                else -> "kannada"
            }
            try {
                Log.d("SpeechUtils", "Calling TTS API with input length: ${truncatedText.length}")
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService(context).textToSpeech(
                        input = truncatedText,
                        apiKey = RetrofitClient.getApiKey(),
                        language = supportedLanguage
                    )
                }
                val audioBytes = withContext(Dispatchers.IO) {
                    response.byteStream().use { it.readBytes() }
                }
                if (audioBytes.isNotEmpty()) {
                    val audioFile = File(context.cacheDir, "temp_tts_audio_${System.currentTimeMillis()}.mp3")
                    val audioUri = Uri.fromFile(audioFile)
                    withContext(Dispatchers.IO) {
                        FileOutputStream(audioFile).use { fos -> fos.write(audioBytes) }
                    }
                    if (audioFile.exists() && audioFile.length() > 0) {
                        // Create a new Message with uri and fileType
                        val updatedMessage = Message(
                            text = message.text,
                            timestamp = message.timestamp,
                            isQuery = message.isQuery,
                            uri = audioUri,
                            fileType = "audio"
                        )
                        withContext(Dispatchers.Main) {
                            val messageIndex = adapter.messages.indexOf(message)
                            if (messageIndex != -1) {
                                adapter.messages[messageIndex] = updatedMessage
                                adapter.notifyItemChanged(messageIndex)
                            }
                            if (autoPlay) {
                                playAudio(
                                    context = context,
                                    audioUri = audioUri,
                                    recyclerView = recyclerView,
                                    adapter = adapter,
                                    message = updatedMessage,
                                    playIconResId = android.R.drawable.ic_media_play,
                                    stopIconResId = R.drawable.ic_media_stop
                                )
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Audio file creation failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "TTS returned empty audio", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: SocketTimeoutException) {
                Log.e("SpeechUtils", "TTS timeout: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Text-to-speech timed out. Please try again.", Toast.LENGTH_LONG).show()
                }
            } catch (e: retrofit2.HttpException) {
                Log.e("SpeechUtils", "TTS HTTP error: ${e.message}, response: ${e.response()?.errorBody()?.string()}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "TTS error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } catch (e: IOException) {
                Log.e("SpeechUtils", "TTS IO error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "TTS network error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("SpeechUtils", "TTS failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "TTS error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    ttsProgressBarVisibility(false)
                }
            }
        }
    }

    fun translate(
        context: Context,
        scope: LifecycleCoroutineScope,
        sentences: List<String>,
        srcLang: String,
        tgtLang: String,
        onSuccess: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val translationRequest = TranslationRequest(sentences, srcLang, tgtLang)

        scope.launch {
            try {
                val response = RetrofitClient.apiService(context).translate(
                    translationRequest,
                    RetrofitClient.getApiKey()
                )
                val translatedText = response.translations.joinToString("\n")
                onSuccess(translatedText)
            } catch (e: Exception) {
                Log.e("SpeechUtils", "Translation failed: ${e.message}", e)
                onError(e)
            }
        }
    }

    fun playAudio(
        context: Context,
        audioUri: Uri,
        recyclerView: RecyclerView,
        adapter: MessageAdapter,
        message: Message,
        playIconResId: Int = android.R.drawable.ic_media_play,
        stopIconResId: Int = R.drawable.ic_media_stop,
        mediaPlayer: MediaPlayer? = null
    ) {
        val player = mediaPlayer ?: MediaPlayer()
        try {
            player.apply {
                reset()
                setDataSource(context, audioUri)
                prepare()
                start()
                val messageIndex = adapter.messages.indexOf(message)
                if (messageIndex != -1) {
                    val holder = recyclerView.findViewHolderForAdapterPosition(messageIndex) as? MessageAdapter.MessageViewHolder
                    holder?.audioControlButton?.setImageResource(stopIconResId)
                }
                setOnCompletionListener {
                    it.release()
                    if (messageIndex != -1) {
                        CoroutineScope(Dispatchers.Main).launch {
                            val holder = recyclerView.findViewHolderForAdapterPosition(messageIndex) as? MessageAdapter.MessageViewHolder
                            holder?.audioControlButton?.setImageResource(playIconResId)
                        }
                    }
                }
                setOnErrorListener { _, what, extra ->
                    CoroutineScope(Dispatchers.Main).launch {
                        Toast.makeText(context, "MediaPlayer error: what=$what, extra=$extra", Toast.LENGTH_LONG).show()
                    }
                    true
                }
            }
        } catch (e: Exception) {
            Log.e("SpeechUtils", "Audio playback failed: ${e.message}", e)
            player.release()
            CoroutineScope(Dispatchers.Main).launch {
                Toast.makeText(context, "Audio playback failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun toggleAudioPlayback(
        context: Context,
        message: Message,
        button: ImageButton,
        recyclerView: RecyclerView,
        adapter: MessageAdapter,
        mediaPlayer: MediaPlayer?,
        playIconResId: Int = android.R.drawable.ic_media_play,
        stopIconResId: Int = R.drawable.ic_media_stop
    ): MediaPlayer? {
        var currentPlayer = mediaPlayer
        if (message.fileType == "audio" && message.uri != null) {
            if (currentPlayer?.isPlaying == true) {
                currentPlayer.stop()
                currentPlayer.release()
                currentPlayer = null
                button.setImageResource(playIconResId)
            } else {
                currentPlayer = MediaPlayer()
                playAudio(
                    context = context,
                    audioUri = message.uri,
                    recyclerView = recyclerView,
                    adapter = adapter,
                    message = message,
                    playIconResId = playIconResId,
                    stopIconResId = stopIconResId,
                    mediaPlayer = currentPlayer
                )
                button.setImageResource(stopIconResId)
            }
        } else {
            Toast.makeText(context, "No audio available for playback", Toast.LENGTH_SHORT).show()
        }
        return currentPlayer
    }
}