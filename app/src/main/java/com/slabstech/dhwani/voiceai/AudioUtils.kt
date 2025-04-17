package com.slabstech.dhwani.voiceai

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

object AudioUtils {
    private const val SAMPLE_RATE = 16000
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private const val MIN_ENERGY_THRESHOLD = 0.04f
    private const val PAUSE_DURATION_MS = 1000L

    fun startContinuousRecording(
        context: Context,
        audioLevelBar: ProgressBar,
        recordingIndicator: View,
        onAudioChunk: (ByteArray) -> Unit
    ) {
        if (!PermissionUtils.checkAndRequestPermission(context, Manifest.permission.RECORD_AUDIO, 101)) {
            Toast.makeText(context, "Microphone permission is required.", Toast.LENGTH_SHORT).show()
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * 4
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )
        var isRecording = true
        audioRecord.startRecording()

        Thread {
            val audioBuffer = ByteArray(bufferSize)
            val recordedData = mutableListOf<Byte>()
            var lastVoiceTime = System.currentTimeMillis()
            var hasVoiceData = false

            (context as? AppCompatActivity)?.runOnUiThread {
                recordingIndicator.visibility = View.VISIBLE
            }

            while (isRecording) {
                val bytesRead = audioRecord.read(audioBuffer, 0, bufferSize)
                if (bytesRead > 0) {
                    val energy = calculateAudioLevel(audioBuffer, bytesRead)
                    val currentTime = System.currentTimeMillis()

                    (context as? AppCompatActivity)?.runOnUiThread {
                        audioLevelBar.progress = (energy * 100).toInt().coerceIn(0, 100)
                    }

                    if (energy > MIN_ENERGY_THRESHOLD) {
                        recordedData.addAll(audioBuffer.take(bytesRead))
                        lastVoiceTime = currentTime
                        hasVoiceData = true
                    } else if (hasVoiceData && (currentTime - lastVoiceTime) >= PAUSE_DURATION_MS) {
                        val audioData = recordedData.toByteArray()
                        onAudioChunk(audioData)
                        recordedData.clear()
                        hasVoiceData = false
                        lastVoiceTime = currentTime
                    } else if (energy <= MIN_ENERGY_THRESHOLD && recordedData.isNotEmpty()) {
                        recordedData.addAll(audioBuffer.take(bytesRead))
                    }
                }
            }

            audioRecord.stop()
            audioRecord.release()
            (context as? AppCompatActivity)?.runOnUiThread {
                recordingIndicator.visibility = View.INVISIBLE
            }
        }.start()
    }

    fun startPushToTalkRecording(
        context: Context,
        audioLevelBar: ProgressBar,
        onRecordingStarted: () -> Unit,
        onRecordingStopped: (File?) -> Unit
    ) {
        if (!PermissionUtils.checkAndRequestPermission(context, Manifest.permission.RECORD_AUDIO, 100)) {
            Toast.makeText(context, "Permission denied", Toast.LENGTH_SHORT).show()
            onRecordingStopped(null)
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        val audioFile = File(context.cacheDir, "temp_audio.wav")
        val audioBuffer = ByteArray(bufferSize)
        val recordedData = mutableListOf<Byte>()
        var isRecording = true

        val recordingStartTime = System.currentTimeMillis()
        audioRecord.startRecording()
        onRecordingStarted()

        Thread {
            try {
                while (isRecording) {
                    val bytesRead = audioRecord.read(audioBuffer, 0, bufferSize)
                    if (bytesRead > 0) {
                        recordedData.addAll(audioBuffer.take(bytesRead))
                        val rms = calculateAudioLevel(audioBuffer, bytesRead)
                        (context as? AppCompatActivity)?.runOnUiThread {
                            audioLevelBar.progress = (rms * 100).toInt().coerceIn(0, 100)
                        }
                    }
                }
            } finally {
                audioRecord.stop()
                audioRecord.release()
                val duration = System.currentTimeMillis() - recordingStartTime
                if (duration >= 1000L) {
                    writeWavFile(recordedData.toByteArray(), audioFile)
                    onRecordingStopped(audioFile)
                } else {
                    onRecordingStopped(null)
                }
            }
        }.start()
    }

    fun stopRecording(audioRecord: AudioRecord?, isRecording: Boolean) {
        if (isRecording) {
            audioRecord?.stop()
            audioRecord?.release()
        }
    }

    fun writeWavFile(pcmData: ByteArray, outputFile: File) {
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
    }

    fun calculateAudioLevel(buffer: ByteArray, bytesRead: Int): Float {
        var sum = 0L
        for (i in 0 until bytesRead step 2) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            sum += sample * sample
        }
        val meanSquare = sum / (bytesRead / 2)
        return sqrt(meanSquare.toDouble()).toFloat() / 32768.0f
    }

    fun playAudioFile(
        context: Context,
        file: File,
        playbackIndicator: View,
        onCompletion: () -> Unit,
        onError: (Exception) -> Unit
    ): MediaPlayer? {
        val mediaPlayer = MediaPlayer()
        try {
            mediaPlayer.setDataSource(file.absolutePath)
            mediaPlayer.prepare()
            mediaPlayer.start()
            (context as? AppCompatActivity)?.runOnUiThread {
                playbackIndicator.visibility = View.VISIBLE
            }
            mediaPlayer.setOnCompletionListener {
                onCompletion()
                mediaPlayer.release()
            }
            mediaPlayer.setOnErrorListener { _, what, extra ->
                onError(Exception("MediaPlayer error: $what, $extra"))
                mediaPlayer.release()
                true
            }
            return mediaPlayer
        } catch (e: Exception) {
            onError(e)
            mediaPlayer.release()
            return null
        }
    }
}