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
import androidx.core.content.FileProvider
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
    private lateinit var cameraButton: ImageButton
    private lateinit var sourceLanguageSpinner: Spinner
    private lateinit var targetLanguageSpinner: Spinner
    private lateinit var toolbar: Toolbar

    // Launcher for gallery images using Photo Picker (Android 13+ recommended, falls back on older)
    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { handleImageUpload(it, false) }
    }

    // Launcher for camera capture
    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            currentPhotoUri?.let { uri -> handleImageUpload(uri, true) }
        } else {
            // Clean up temp file if camera capture failed
            photoFile?.let { file ->
                if (file.exists()) {
                    file.delete()
                }
            }
        }
        // Clean up references
        photoFile = null
        currentPhotoUri = null
    }

    private var photoFile: File? = null
    private var currentPhotoUri: Uri? = null

    private val READ_STORAGE_PERMISSION_CODE = 101
    private val CAMERA_PERMISSION_CODE = 102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("TranslateActivity", "onCreate called")
        setContentView(R.layout.activity_translate)

        historyRecyclerView = findViewById(R.id.historyRecyclerView)
        progressBar = findViewById(R.id.progressBar)
        textQueryInput = findViewById(R.id.textQueryInput)
        sendButton = findViewById(R.id.sendButton)
        attachImageButton = findViewById(R.id.attachImageButton)
        cameraButton = findViewById(R.id.cameraButton)
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

        // Request camera permission if not granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE
            )
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

        cameraButton.setOnClickListener {
            launchCamera()
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

    private fun launchCamera() {
        val cameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        if (cameraPermission != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
            return
        }
        currentPhotoUri = createTempImageFileUri()
        currentPhotoUri?.let { takePictureLauncher.launch(it) }
    }

    private fun createTempImageFileUri(): Uri? {
        return try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val imageFileName = "JPEG_" + timeStamp + "_"
            photoFile = File.createTempFile(imageFileName, ".jpg", cacheDir)
            FileProvider.getUriForFile(this, "${packageName}.fileprovider", photoFile!!)
        } catch (e: Exception) {
            Log.e("TranslateActivity", "Failed to create temp image file: ${e.message}", e)
            Toast.makeText(this, "Failed to prepare camera", Toast.LENGTH_SHORT).show()
            null
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

    private fun handleImageUpload(uri: Uri, isFromCamera: Boolean) {
        Log.d("TranslateActivity", "Handling image upload for URI: $uri, fromCamera: $isFromCamera")
        val fileName = getFileName(uri)
        Log.d("TranslateActivity", "File name: $fileName")
        val query = "Extract text from image"

        var tempFile: File? = null

        try {
            if (isFromCamera && photoFile != null && photoFile!!.exists()) {
                // For camera, use the photoFile directly
                tempFile = photoFile
                Log.d("TranslateActivity", "Using camera photo file: ${tempFile!!.absolutePath}, size: ${tempFile!!.length()}")
            } else {
                // For gallery, copy from inputStream
                val inputStream = contentResolver.openInputStream(uri)
                tempFile = File(cacheDir, fileName ?: "temp_image.jpg")

                inputStream?.use { input ->
                    FileOutputStream(tempFile!!).use { output ->
                        input.copyTo(output)
                    }
                } ?: run {
                    Log.e("TranslateActivity", "InputStream is null for URI: $uri")
                    Toast.makeText(this, "Failed to read the selected image. URI scheme: ${uri.scheme}, authority: ${uri.authority}", Toast.LENGTH_LONG).show()
                    return
                }

                Log.d("TranslateActivity", "Copied gallery file to: ${tempFile!!.absolutePath}, size: ${tempFile!!.length()}")
            }

            // Check if file was successfully created and has content
            if (tempFile == null || !tempFile!!.exists() || tempFile!!.length() == 0L) {
                Log.e("TranslateActivity", "Temp file invalid: exists=${tempFile?.exists()}, size=${tempFile?.length()}")
                Toast.makeText(this, "Failed to read the selected image.", Toast.LENGTH_SHORT).show()
                return
            }

            if (!isImageFile(fileName ?: "")) {
                Toast.makeText(this, "Please select an image file", Toast.LENGTH_SHORT).show()
                return
            }

            val compressedFile = compressImage(tempFile!!)
            processImageUpload(compressedFile, uri, query, "image")

            // Clean up original temp file for camera after successful processing
            if (isFromCamera) {
                photoFile = null
            }
        } catch (e: Exception) {
            Log.e("TranslateActivity", "File processing failed: ${e.message}", e)
            Toast.makeText(this, "File processing failed: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            if (!isFromCamera) {
                tempFile?.delete() // Clean up gallery temp file if not camera
            }
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
                lowerCaseName.endsWith(".png") ||
                lowerCaseName.endsWith(".heic") // Add support for HEIC if needed
    }

    private fun compressImage(inputFile: File): File {
        val maxSize = 1_000_000
        val outputFile = File(cacheDir, "compressed_${System.currentTimeMillis()}.png")

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
                ?: throw IllegalArgumentException("Cannot decode bitmap from file: ${inputFile.absolutePath}")

            // For PNG, compress with quality 0-100 (though PNG is lossless, this affects compression level)
            val baos = ByteArrayOutputStream()
            var quality = 100
            do {
                baos.reset()
                bitmap.compress(Bitmap.CompressFormat.PNG, quality, baos)
                if (quality <= 0) break
                quality -= 10
            } while (baos.size() > maxSize)

            if (baos.size() > maxSize) {
                // If still too large, resize the bitmap further
                val resizeFactor = Math.sqrt((baos.size() / maxSize).toDouble()).toInt()
                if (resizeFactor > 1) {
                    val resizedBitmap = Bitmap.createScaledBitmap(bitmap, bitmap.width / resizeFactor, bitmap.height / resizeFactor, true)
                    bitmap.recycle()
                    bitmap = resizedBitmap
                    baos.reset()
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
                }
            }

            FileOutputStream(outputFile).use { fos ->
                fos.write(baos.toByteArray())
            }

            bitmap.recycle()
            Log.d("TranslateActivity", "Compressed file: ${outputFile.absolutePath}, size: ${outputFile.length()}")
            return outputFile
        } catch (e: Exception) {
            Log.e("TranslateActivity", "Image compression failed: ${e.message}", e)
            throw e
        }
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
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

        // Encrypt the query and languages for consistency with text translation API
        val encryptedQuery = RetrofitClient.encryptText(query)
        val encryptedSrcLang = RetrofitClient.encryptText(srcLang)
        val encryptedTgtLang = RetrofitClient.encryptText(tgtLang)

        lifecycleScope.launch(Dispatchers.IO) {
            runOnUiThread { progressBar.visibility = View.VISIBLE }
            try {
                val requestFile = file.asRequestBody("image/png".toMediaType())
                val filePart = MultipartBody.Part.createFormData("file", file.name, requestFile)
                val queryPart = encryptedQuery.toRequestBody("text/plain".toMediaType())
                Log.d("TranslateActivity", "File part - name: ${file.name}, size: ${file.length()}")
                Log.d("TranslateActivity", "Encrypted Query: $encryptedQuery, src_lang: $encryptedSrcLang, tgt_lang: $encryptedTgtLang")
                val response = RetrofitClient.apiService(this@TranslateActivity).visualQuery(
                    filePart,
                    queryPart,
                    encryptedSrcLang,
                    encryptedTgtLang,
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
                if (file.exists()) {
                    file.delete()
                }
            }
        }
    }

    override fun toggleAudioPlayback(message: Message, button: ImageButton) {
        // No audio functionality in TranslateActivity
    }

    override fun onDestroy() {
        super.onDestroy()
        // Final cleanup for any lingering camera temp file (unlikely, but safe)
        photoFile?.let { file ->
            if (file.exists()) {
                file.delete()
            }
        }
        photoFile = null
        currentPhotoUri = null
        Log.d("TranslateActivity", "onDestroy called")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            READ_STORAGE_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted, user can now pick images from storage
                } else {
                    Toast.makeText(this, "Storage permission denied. Cannot access gallery images.", Toast.LENGTH_SHORT).show()
                }
            }
            CAMERA_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted, can now launch camera
                    launchCamera()
                } else {
                    Toast.makeText(this, "Camera permission denied. Cannot take photos.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}