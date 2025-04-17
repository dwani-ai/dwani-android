package com.slabstech.dhwani.voiceai

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.gson.Gson
import com.slabstech.dhwani.voiceai.utils.SpeechUtils
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class DocsActivity : MessageActivity() {

    private val READ_STORAGE_PERMISSION_CODE = 101
    private lateinit var progressBar: ProgressBar
    private lateinit var ttsProgressBar: ProgressBar
    private lateinit var attachFab: FloatingActionButton
    private lateinit var toolbar: Toolbar

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleFileUpload(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("DocsActivity", "onCreate called")
        setContentView(R.layout.activity_docs)

        historyRecyclerView = findViewById(R.id.historyRecyclerView)
        progressBar = findViewById(R.id.progressBar)
        ttsProgressBar = findViewById(R.id.ttsProgressBar)
        attachFab = findViewById(R.id.attachFab)
        toolbar = findViewById(R.id.toolbar)

        setSupportActionBar(toolbar)
        setupMessageList()
        setupBottomNavigation(R.id.nav_docs)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                READ_STORAGE_PERMISSION_CODE
            )
        }

        attachFab.setOnClickListener {
            pickFileLauncher.launch("*/*")
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d("DocsActivity", "onResume called")
    }

    private fun handleFileUpload(uri: Uri) {
        val fileName = getFileName(uri)
        val inputStream = contentResolver.openInputStream(uri)
        var file = File(cacheDir, fileName)

        val isImage = fileName.lowercase().endsWith(".jpg") ||
                fileName.lowercase().endsWith(".jpeg") ||
                fileName.lowercase().endsWith(".png")

        if (isImage) {
            try {
                inputStream?.use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
                file = compressImage(file)
            } catch (e: Exception) {
                Log.e("DocsActivity", "Image compression failed: ${e.message}", e)
                Toast.makeText(this, "Image processing failed: ${e.message}", Toast.LENGTH_LONG).show()
                return
            } finally {
                inputStream?.close()
            }
        } else {
            inputStream?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
        }

        val fileBytes = file.readBytes()
        val encryptedFileBytes = RetrofitClient.encryptAudio(fileBytes, sessionKey)
        val encryptedFile = File(cacheDir, "encrypted_$fileName")
        FileOutputStream(encryptedFile).use { it.write(encryptedFileBytes) }

        val defaultQuery = "Describe the image"
        val timestamp = DateUtils.getCurrentTimestamp()
        val message = Message(defaultQuery, timestamp, true, uri)
        messageList.add(message)
        messageAdapter.notifyItemInserted(messageList.size - 1)
        scrollToLatestMessage()
        getVisualQueryResponse(defaultQuery, encryptedFile)
    }

    private fun compressImage(inputFile: File): File {
        val maxSize = 1_000_000
        val outputFile = File(cacheDir, "compressed_${inputFile.name}")

        try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                BitmapFactory.decodeFile(inputFile.absolutePath, this)

                var sampleSize = 1
                if (outWidth > 1000 || outHeight > 1000) {
                    val scale = maxOf(outWidth, outHeight) / 1000.0
                    sampleSize = Math.pow(2.0, Math.ceil(Math.log(scale) / Math.log(2.0))).toInt()
                }
                inSampleSize = sampleSize
                inJustDecodeBounds = false
            }

            var bitmap = BitmapFactory.decodeFile(inputFile.absolutePath, options)

            var quality = 90
            val baos = ByteArrayOutputStream()

            do {
                baos.reset()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                quality -= 10
            } while (baos.size() > maxSize && quality > 10)

            FileOutputStream(outputFile).use { fos ->
                fos.write(baos.toByteArray())
            }

            bitmap.recycle()
            return outputFile

        } catch (e: Exception) {
            Log.e("DocsActivity", "Image compression failed: ${e.message}", e)
            throw e
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "unknown_file"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex != -1) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    private fun getVisualQueryResponse(query: String, file: File) {
        val token = AuthManager.getToken(this) ?: return
        val selectedLanguage = prefs.getString("language", "kannada") ?: "kannada"
        val languageMap = mapOf(
            "english" to "eng_Latn",
            "hindi" to "hin_Deva",
            "kannada" to "kan_Knda",
            "tamil" to "tam_Taml",
            "malayalam" to "mal_Mlym",
            "telugu" to "tel_Telu",
            "german" to "deu_Latn",
            "french" to "fra_Latn",
            "dutch" to "nld_Latn",
            "spanish" to "spa_Latn",
            "italian" to "ita_Latn",
            "portuguese" to "por_Latn",
            "russian" to "rus_Cyrl",
            "polish" to "pol_Latn"
        )
        val srcLang = languageMap[selectedLanguage] ?: "kan_Knda"
        val tgtLang = srcLang

        val encryptedQuery = RetrofitClient.encryptText(query, sessionKey)
        val encryptedSrcLang = RetrofitClient.encryptText(srcLang, sessionKey)
        val encryptedTgtLang = RetrofitClient.encryptText(tgtLang, sessionKey)

        val visualQueryRequest = VisualQueryRequest(encryptedQuery, encryptedSrcLang, encryptedTgtLang)
        val jsonBody = Gson().toJson(visualQueryRequest)
        val dataBody = jsonBody.toRequestBody("application/json".toMediaType())

        lifecycleScope.launch {
            ApiUtils.performApiCall(
                context = this@DocsActivity,
                progressBar = progressBar,
                apiCall = {
                    val requestFile = file.asRequestBody("application/octet-stream".toMediaType())
                    val filePart = MultipartBody.Part.createFormData("file", file.name, requestFile)
                    val cleanSessionKey = Base64.encodeToString(sessionKey, Base64.NO_WRAP)
                    RetrofitClient.apiService(this@DocsActivity).visualQuery(
                        filePart,
                        dataBody,
                        "Bearer $token",
                        cleanSessionKey
                    )
                },
                onSuccess = { response ->
                    val answerText = response.answer
                    val timestamp = DateUtils.getCurrentTimestamp()
                    val message = Message("Answer: $answerText", timestamp, false)
                    messageList.add(message)
                    messageAdapter.notifyItemInserted(messageList.size - 1)
                    scrollToLatestMessage()

                    val encryptedAnswerText = RetrofitClient.encryptText(answerText, sessionKey)
                    SpeechUtils.textToSpeech(
                        context = this@DocsActivity,
                        scope = lifecycleScope,
                        text = encryptedAnswerText,
                        message = message,
                        recyclerView = historyRecyclerView,
                        adapter = messageAdapter,
                        ttsProgressBarVisibility = { visible -> ttsProgressBar.visibility = if (visible) View.VISIBLE else View.GONE },
                        srcLang = tgtLang,
                        sessionKey = sessionKey
                    )
                },
                onError = { e -> Log.e("DocsActivity", "Visual query failed: ${e.message}", e) }
            )
        }
    }

    override fun toggleAudioPlayback(message: Message, button: ImageButton) {
        // No audio playback in DocsActivity
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == READ_STORAGE_PERMISSION_CODE && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            pickFileLauncher.launch("*/*")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("DocsActivity", "onDestroy called")
    }
}