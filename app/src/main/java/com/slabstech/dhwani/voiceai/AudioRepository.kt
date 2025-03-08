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
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioRepository(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
) {
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val retryDelayMs = 2000L
    private val minRecordingDurationMs = 500L

    suspend fun startRecording(): Pair<FloatArray?, File?> =
        withContext(Dispatchers.IO) {
            Timber.d("startRecording: Checking permission")
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Timber.e("RECORD_AUDIO permission not granted")
                return@withContext Pair(null, null)
            }

            Timber.d("startRecording: Initializing AudioRecord")
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val audioRecord =
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize,
                )
            val audioFile = File(context.cacheDir, "temp_audio_${System.currentTimeMillis()}.wav")
            val audioBuffer = ByteArray(bufferSize)
            val recordedData = mutableListOf<Byte>()

            Timber.d("startRecording: Setting isRecording = true")
            isRecording = true
            val recordingStartTime = System.currentTimeMillis()

            try {
                Timber.d("startRecording: Starting recording")
                audioRecord.startRecording()
                while (isRecording) {
                    val bytesRead = audioRecord.read(audioBuffer, 0, bufferSize)
                    Timber.d("startRecording: Bytes read = $bytesRead")
                    if (bytesRead > 0) {
                        recordedData.addAll(audioBuffer.take(bytesRead))
                    } else if (bytesRead < 0) {
                        Timber.e("startRecording: Error reading audio data, code = $bytesRead")
                        break
                    }
                }
                Timber.d("startRecording: Stopping recording")
                audioRecord.stop()
                audioRecord.release()

                val duration = System.currentTimeMillis() - recordingStartTime
                Timber.d("Recording duration: $duration ms")
                if (duration >= minRecordingDurationMs) {
                    writeWavFile(recordedData.toByteArray(), audioFile)
                    val rms = calculateRMS(recordedData.toByteArray())
                    return@withContext Pair(rms, audioFile)
                } else {
                    Timber.w("Recording too short: $duration ms, deleting file")
                    audioFile.delete()
                    return@withContext Pair(null, null)
                }
            } catch (e: IllegalStateException) {
                Timber.e(e, "AudioRecord failed to start or stop")
                audioRecord.release()
                audioFile.delete()
                return@withContext Pair(null, null)
            } catch (e: Exception) {
                Timber.e(e, "Recording failed unexpectedly")
                audioRecord.stop()
                audioRecord.release()
                audioFile.delete()
                return@withContext Pair(null, null)
            } finally {
                Timber.d("startRecording: Cleanup, setting isRecording = false")
                isRecording = false
            }
        }

    var isRecording: Boolean = false
        get() = field
        set(value) {
            Timber.d("isRecording set to $value")
            field = value
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
            Timber.d("WAV file written: ${file.absolutePath}, size: ${file.length()} bytes")
        } catch (e: IOException) {
            Timber.e(e, "Failed to write WAV file")
            file.delete()
        }
    }

    private suspend fun isNetworkAvailable(): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val address = InetAddress.getByName("8.8.8.8")
                address.isReachable(2000)
            } catch (e: Exception) {
                Timber.e(e, "Network check failed")
                false
            }
        }

    suspend fun sendAudioToApi(audioFile: File?): Pair<String?, String?> =
        withContext(Dispatchers.IO) {
            if (audioFile == null || !audioFile.exists()) {
                Timber.e("Audio file is null or does not exist")
                return@withContext Pair(null, "Audio file not found")
            }

            if (!isNetworkAvailable()) {
                Timber.e("No network available")
                return@withContext Pair(null, "No network connection")
            }

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

            try {
                val pingRequest =
                    Request.Builder()
                        .url(transcriptionApiEndpoint)
                        .head()
                        .build()
                okHttpClient.newCall(pingRequest).execute().use { response ->
                    Timber.d("Ping response code: ${response.code}")
                }
            } catch (e: IOException) {
                Timber.w(e, "Ping failed, proceeding anyway")
            }

            var attempts = 0
            var success = false
            var responseBody: String? = null

            while (attempts < maxRetries && !success) {
                try {
                    val response = okHttpClient.newCall(request).execute()
                    Timber.d("API Response Code: ${response.code}")
                    if (response.isSuccessful) {
                        responseBody = response.body?.string()
                        Timber.d("API Response Body: $responseBody")
                        success = true
                    } else {
                        Timber.e("API failed with code: ${response.code}, message: ${response.message}")
                        attempts++
                        if (attempts < maxRetries) Thread.sleep(retryDelayMs)
                    }
                    response.close()
                } catch (e: IOException) {
                    Timber.e(e, "API call failed on attempt $attempts: ${e.message}")
                    attempts++
                    if (attempts < maxRetries) Thread.sleep(retryDelayMs)
                } catch (e: Exception) {
                    Timber.e(e, "Unexpected error on attempt $attempts: ${e.message}")
                    return@withContext Pair(null, "Unexpected error: ${e.message}")
                }
            }

            return@withContext if (success && responseBody != null) {
                try {
                    val json = JSONObject(responseBody)
                    val text = json.optString("text", "")
                    if (text.isNotEmpty() && !json.has("error")) {
                        Timber.d("Transcription successful: $text")
                        Pair(text, null)
                    } else {
                        Timber.e("Invalid response: $responseBody")
                        Pair(null, "Voice query empty or invalid")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to parse response: $responseBody")
                    Pair(null, "Response parsing error: ${e.message}")
                }
            } else {
                Timber.e("Failed after $maxRetries retries")
                Pair(null, "Failed after $maxRetries retries")
            }
        }
}
