package com.slabstech.dhwani.voiceai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioRepository(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
) {
    var audioFile: File? = null
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val retryDelayMs = 2000L
    private val minRecordingDurationMs = 1000L
    private var recordingStartTime: Long = 0L

    suspend fun startRecording(): FloatArray? =
        withContext(Dispatchers.IO) {
            // Check RECORD_AUDIO permission
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                // Permission not granted; return null to let caller handle it
                return@withContext null
            }

            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            audioRecord =
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize,
                )
            audioFile = File(context.cacheDir, "temp_audio.wav")
            val audioBuffer = ByteArray(bufferSize)
            val recordedData = mutableListOf<Byte>()

            isRecording = true
            recordingStartTime = System.currentTimeMillis()
            audioRecord?.startRecording()

            try {
                while (isRecording) {
                    val bytesRead = audioRecord?.read(audioBuffer, 0, bufferSize) ?: 0
                    if (bytesRead > 0) {
                        recordedData.addAll(audioBuffer.take(bytesRead))
                    }
                }
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null

                val duration = System.currentTimeMillis() - recordingStartTime
                if (duration >= minRecordingDurationMs && audioFile != null) {
                    writeWavFile(recordedData.toByteArray(), audioFile!!)
                    return@withContext calculateRMS(recordedData.toByteArray())
                } else {
                    audioFile?.delete()
                    return@withContext null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
                audioFile?.delete()
                return@withContext null
            }
        }

    fun stopRecording() {
        isRecording = false
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

    private fun writeWavFile(
        pcmData: ByteArray,
        file: File,
    ) {
        try {
            FileOutputStream(file).use { fos ->
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

    suspend fun sendAudioToApi(audioFile: File?): Pair<String?, String?> =
        withContext(Dispatchers.IO) {
            if (audioFile == null || !audioFile.exists()) return@withContext Pair(null, "Audio file not found")

            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val language = prefs.getString("language", "kannada") ?: "kannada"
            val maxRetries = prefs.getString("max_retries", "3")?.toIntOrNull() ?: 3
            val transcriptionApiEndpoint =
                prefs.getString(
                    "transcription_api_endpoint",
                    "https://gaganyatri-asr-indic-server-cpu.hf.space/transcribe/",
                ) ?: "https://gaganyatri-asr-indic-server-cpu.hf.space/transcribe/"

            val requestBody =
                MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file",
                        audioFile.name,
                        audioFile.asRequestBody("audio/x-wav".toMediaType()),
                    )
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
                    val response = okHttpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        responseBody = response.body?.string()
                        success = true
                    } else {
                        attempts++
                        if (attempts < maxRetries) Thread.sleep(retryDelayMs)
                    }
                } catch (e: IOException) {
                    attempts++
                    if (attempts < maxRetries) Thread.sleep(retryDelayMs)
                }
            }

            return@withContext if (success && responseBody != null) {
                val json = JSONObject(responseBody)
                val text = json.optString("text", "")
                if (text.isNotEmpty() && !json.has("error")) {
                    Pair(text, null)
                } else {
                    Pair(null, "Voice query empty or invalid")
                }
            } else {
                Pair(null, "Failed after $maxRetries retries")
            }
        }
}
