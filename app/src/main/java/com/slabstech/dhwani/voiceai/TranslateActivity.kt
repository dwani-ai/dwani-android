package com.slabstech.dhwani.voiceai

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*

class TranslateActivity : MessageActivity() {

    private lateinit var progressBar: ProgressBar
    private lateinit var textQueryInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var attachImageButton: ImageButton
    private lateinit var sourceLanguageSpinner: Spinner
    private lateinit var targetLanguageSpinner: Spinner
    private lateinit var toolbar: Toolbar

    // Launcher for images using Photo Picker (Android 13+ recommended, falls back on older)
    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { handleImageUpload(it) }
    }

    private val READ_STORAGE_PERMISSION_CODE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("TranslateActivity", "onCreate called")
        setContentView(R.layout.activity_translate)

        historyRecyclerView = findViewById(R.id.historyRecyclerView)
        progressBar = findViewById(R.id.progressBar)
        textQueryInput = findViewById(R.id.textQueryInput)
        sendButton = findViewById(R.id.sendButton)
        attachImageButton = findViewById(R.id.attachImageButton)
        sourceLanguageSpinner = findViewById(R.id.sourceLanguageSpinner)
        targetLanguageSpinner = findViewById(R.id.targetLanguageSpinner)
        toolbar = findViewById(R.id.toolbar)

        setSupportActionBar(toolbar)
        setupMessageList()
        setupBottomNavigation(R.id.nav_translate)

        // Conditional permission request: Only for pre-Android 13 where Photo Picker isn't available
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    READ_STORAGE_PERMISSION_CODE
                )
            }
        }

        // Set default source language to Kannada
        val languageValues = resources.getStringArray(R.array.language_values)
        val defaultSourceIndex = languageValues.indexOf("kannada")
        if (defaultSourceIndex != -1) {
            sourceLanguageSpinner.setSelection(defaultSourceIndex)
        }

        sendButton.setOnClickListener {
            val query = textQueryInput.text.toString().trim()
            if (query.isNotEmpty()) {
                val timestamp = DateUtils.getCurrentTimestamp()
                val message = Message("Input: $query", timestamp, true, null, null)
                messageList.add(message)
                messageAdapter.notifyItemInserted(messageList.size - 1)
                scrollToLatestMessage()
                getTranslationResponse(query)
                textQueryInput.text.clear()
            } else {
                Toast.makeText(this, "Please enter a sentence", Toast.LENGTH_SHORT).show()
            }
        }

        attachImageButton.setOnClickListener {
            // Use Photo Picker for images (permissionless on Android 13+)
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        textQueryInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (s.isNullOrEmpty()) {
                    sendButton.visibility = View.GONE
                } else {
                    sendButton.visibility = View.VISIBLE
                }
            }
        })

        // Optional: Handle spinner item selection changes if needed
        sourceLanguageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // Handle source language change if needed
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Handle no selection
            }
        }

        targetLanguageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // Handle target language change if needed
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Handle no selection
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d("TranslateActivity", "onResume called")
    }

    private fun getTranslationResponse(input: String) {
        // Removed unnecessary token check as API uses X-API-Key
        val sourceIndex = sourceLanguageSpinner.selectedItemPosition
        val languageValues = resources.getStringArray(R.array.language_values)
        val selectedLanguage = languageValues[sourceIndex]
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
        val tgtLang = resources.getStringArray(R.array.target_language_codes)[targetLanguageSpinner.selectedItemPosition]

        val words = input.split("\\s+".toRegex()).filter { it.isNotBlank() }
        val sentences = mutableListOf<String>()
        var currentSentence = mutableListOf<String>()
        var wordCount = 0

        for (word in words) {
            if (wordCount + 1 > 15 && currentSentence.isNotEmpty()) {
                sentences.add(currentSentence.joinToString(" "))
                currentSentence = mutableListOf()
                wordCount = 0
            }
            currentSentence.add(word)
            wordCount++
            if (word.endsWith('.') || word.endsWith('!') || word.endsWith('?')) {
                sentences.add(currentSentence.joinToString(" "))
                currentSentence = mutableListOf()
                wordCount = 0
            }
        }
        if (currentSentence.isNotEmpty()) {
            sentences.add(currentSentence.joinToString(" "))
        }

        val encryptedSentences = sentences.map { RetrofitClient.encryptText(it) }
        val encryptedSrcLang = RetrofitClient.encryptText(srcLang)
        val encryptedTgtLang = RetrofitClient.encryptText(tgtLang)

        val translationRequest = TranslationRequest(encryptedSentences, encryptedSrcLang, encryptedTgtLang)

        lifecycleScope.launch {
            ApiUtils.performApiCall(
                context = this@TranslateActivity,
                progressBar = progressBar,
                apiCall = {
                    RetrofitClient.apiService(this@TranslateActivity).translate(
                        translationRequest,
                        RetrofitClient.getApiKey()
                    )
                },
                onSuccess = { response ->
                    val translatedText = response.translations.joinToString("\n")
                    val timestamp = DateUtils.getCurrentTimestamp()
                    val message = Message("Translation: $translatedText", timestamp, false, null, null)
                    messageList.add(message)
                    messageAdapter.notifyItemInserted(messageList.size - 1)
                    scrollToLatestMessage()
                },
                onError = { e -> Log.e("TranslateActivity", "Translation failed: ${e.message}", e) }
            )
        }
    }

    private fun handleImageUpload(uri: Uri) {
        val fileName = getFileName(uri)
        val inputStream = contentResolver.openInputStream(uri)
        val file = File(cacheDir, fileName)
        val query = "Extract text from image"

        try {
            inputStream?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }

            if (!isImageFile(fileName)) {
                Toast.makeText(this, "Please select an image file", Toast.LENGTH_SHORT).show()
                return
            }
            val compressedFile = compressImage(file)
            processImageUpload(compressedFile, uri, query, "image")
        } catch (e: Exception) {
            Log.e("TranslateActivity", "File processing failed: ${e.message}", e)
            Toast.makeText(this, "File processing failed: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            inputStream?.close()
        }
    }

    private fun processImageUpload(file: File, uri: Uri, query: String, fileType: String) {
        val timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val message = Message("Image translation...", timestamp, true, uri, fileType)
        messageList.add(message)
        messageAdapter.notifyItemInserted(messageList.size - 1)
        historyRecyclerView.requestLayout()
        scrollToLatestMessage()
        getVisualQueryResponse(query, file, fileType)
    }

    private fun isImageFile(fileName: String): Boolean {
        val lowerCaseName = fileName.lowercase()
        return lowerCaseName.endsWith(".jpg") ||
                lowerCaseName.endsWith(".jpeg") ||
                lowerCaseName.endsWith(".png")
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
            Log.e("TranslateActivity", "Image compression failed: ${e.message}", e)
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

    private fun getVisualQueryResponse(query: String, file: File, fileType: String) {
        val sourceIndex = sourceLanguageSpinner.selectedItemPosition
        val languageValues = resources.getStringArray(R.array.language_values)
        val selectedLanguage = languageValues[sourceIndex]
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
        val srcLang: String = languageMap[selectedLanguage] ?: "kan_Knda"
        val tgtLang = resources.getStringArray(R.array.target_language_codes)[targetLanguageSpinner.selectedItemPosition]

        lifecycleScope.launch(Dispatchers.IO) {
            runOnUiThread { progressBar.visibility = View.VISIBLE }
            try {
                val requestFile = file.asRequestBody("image/png".toMediaType())
                val filePart = MultipartBody.Part.createFormData("file", file.name, requestFile)
                val queryPart = query.toRequestBody("text/plain".toMediaType())
                Log.d("TranslateActivity", "File part - name: ${file.name}, size: ${file.length()}")
                Log.d("TranslateActivity", "Query: $query, src_lang: $srcLang, tgt_lang: $tgtLang")
                val response = RetrofitClient.apiService(this@TranslateActivity).visualQuery(
                    filePart,
                    queryPart,
                    srcLang,
                    tgtLang,
                    RetrofitClient.getApiKey()
                )
                val answerText = response.answer
                val timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val message = Message("Translation: $answerText", timestamp, false, null, null) // No uri/fileType for response
                runOnUiThread {
                    messageList.add(message)
                    messageAdapter.notifyItemInserted(messageList.size - 1)
                    historyRecyclerView.requestLayout()
                    scrollToLatestMessage()
                }
            } catch (e: Exception) {
                Log.e("TranslateActivity", "Image translation failed: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(this@TranslateActivity, "Image translation error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                runOnUiThread { progressBar.visibility = View.GONE }
                file.delete()
            }
        }
    }

    override fun toggleAudioPlayback(message: Message, button: ImageButton) {
        // No audio functionality in TranslateActivity
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("TranslateActivity", "onDestroy called")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == READ_STORAGE_PERMISSION_CODE && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            // Permission granted, user can now pick images
        }
    }
}