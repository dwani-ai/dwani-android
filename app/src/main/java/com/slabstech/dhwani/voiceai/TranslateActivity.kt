package com.slabstech.dhwani.voiceai

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.Toast
import androidx.preference.PreferenceManager
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.slabstech.dhwani.voiceai.repository.SessionRepository
import com.slabstech.dhwani.voiceai.repository.SessionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private lateinit var swapLanguagesButton: ImageButton
    private lateinit var toolbar: Toolbar

    private val translatePrefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    companion object {
        private const val PREF_TRANSLATE_SOURCE_VALUE = "translate_source_language_value"
        private const val PREF_TRANSLATE_TARGET_CODE = "translate_target_language_code"

        /** Lowercase keys from [R.array.language_values] → API script codes. */
        private val LANGUAGE_VALUE_TO_API_CODE: Map<String, String> = mapOf(
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
    }

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
    private var messageCollectionJob: kotlinx.coroutines.Job? = null

    private val CAMERA_PERMISSION_CODE = 102
    private var pendingCameraAfterPermission = false

    override fun getSessionRepository(): SessionRepository = (application as DhwaniApp).sessionRepository

    private fun loadMessagesForSession(sessionId: String) {
        messageCollectionJob?.cancel()
        messageCollectionJob = lifecycleScope.launch {
            getSessionRepository().getMessages(sessionId).collectLatest { list ->
                withContext(Dispatchers.Main) {
                    messageList.clear()
                    messageList.addAll(list)
                    messageAdapter.notifyDataSetChanged()
                    updateEmptyState()
                    scrollToLatestMessage()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_translate)
        setupAnswerStyleWindowInsets()

        historyRecyclerView = findViewById(R.id.historyRecyclerView)
        progressBar = findViewById(R.id.progressBar)
        textQueryInput = findViewById(R.id.textQueryInput)
        sendButton = findViewById(R.id.sendButton)
        attachImageButton = findViewById(R.id.attachImageButton)
        cameraButton = findViewById(R.id.cameraButton)
        sourceLanguageSpinner = findViewById(R.id.sourceLanguageSpinner)
        targetLanguageSpinner = findViewById(R.id.targetLanguageSpinner)
        swapLanguagesButton = findViewById(R.id.swapLanguagesButton)
        toolbar = findViewById(R.id.toolbar)

        setSupportActionBar(toolbar)
        emptyStateView = findViewById(R.id.emptyStateView)
        setupMessageList()
        setupBottomNavigation(R.id.nav_translate)

        findViewById<View>(R.id.toolbarSubtitle)?.setOnClickListener { showSessionListBottomSheet() }

        val restoredSessionId = savedInstanceState?.getString(MessageActivity.STATE_CURRENT_SESSION_ID)
        val intentSessionId = intent.getStringExtra("SESSION_ID")
        lifecycleScope.launch {
            val session = withContext(Dispatchers.IO) {
                when {
                    restoredSessionId != null -> {
                        getSessionRepository().getSession(restoredSessionId)
                            ?: getSessionRepository().createSession(SessionType.TRANSLATE, null)
                    }
                    intentSessionId != null -> {
                        getSessionRepository().getSession(intentSessionId)
                            ?: getSessionRepository().createSession(SessionType.TRANSLATE, null)
                    }
                    else -> {
                        getSessionRepository().createSession(SessionType.TRANSLATE, null)
                    }
                }
            }
            withContext(Dispatchers.Main) {
                currentSessionId = session.id
                loadMessagesForSession(session.id)
            }
        }

        applySavedLanguageSelections()

        swapLanguagesButton.setOnClickListener { swapSourceAndTargetLanguages() }

        sendButton.setOnClickListener {
            val query = textQueryInput.text.toString().trim()
            if (query.isNotEmpty()) {
                val sessionId = currentSessionId ?: return@setOnClickListener
                val timestamp = DateUtils.getCurrentTimestamp()
                lifecycleScope.launch(Dispatchers.IO) {
                    getSessionRepository().addMessage(sessionId, "Input: $query", timestamp, isQuery = true, null, null)
                }
                getTranslationResponse(query)
                textQueryInput.text.clear()
            } else {
                Toast.makeText(this, "Please enter a sentence", Toast.LENGTH_SHORT).show()
            }
        }

        attachImageButton.setOnClickListener {
            launchGalleryPicker()
        }

        cameraButton.setOnClickListener {
            launchCamera()
        }

        sendButton.visibility = View.VISIBLE
        textQueryInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val hasText = !s.isNullOrEmpty()
                sendButton.isEnabled = hasText
                sendButton.alpha = if (hasText) 1f else 0.5f
            }
        })
        sendButton.isEnabled = false
        sendButton.alpha = 0.5f

        sourceLanguageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                saveLanguageSelections()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        targetLanguageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                saveLanguageSelections()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun applySavedLanguageSelections() {
        val languageValues = resources.getStringArray(R.array.language_values)
        val targetCodes = resources.getStringArray(R.array.target_language_codes)
        val savedSource = translatePrefs.getString(PREF_TRANSLATE_SOURCE_VALUE, null)
        val savedTargetCode = translatePrefs.getString(PREF_TRANSLATE_TARGET_CODE, null)

        val sourceIndex = when {
            savedSource != null -> languageValues.indexOf(savedSource).takeIf { it >= 0 }
            else -> null
        } ?: languageValues.indexOf("kannada").takeIf { it >= 0 } ?: 0

        val targetIndex = when {
            savedTargetCode != null -> targetCodes.indexOf(savedTargetCode).takeIf { it >= 0 }
            else -> null
        } ?: targetCodes.indexOf("eng_Latn").takeIf { it >= 0 } ?: 0

        sourceLanguageSpinner.setSelection(sourceIndex, false)
        targetLanguageSpinner.setSelection(targetIndex, false)
    }

    private fun saveLanguageSelections() {
        val languageValues = resources.getStringArray(R.array.language_values)
        val targetCodes = resources.getStringArray(R.array.target_language_codes)
        val srcPos = sourceLanguageSpinner.selectedItemPosition
        val tgtPos = targetLanguageSpinner.selectedItemPosition
        if (srcPos !in languageValues.indices || tgtPos !in targetCodes.indices) return
        translatePrefs.edit()
            .putString(PREF_TRANSLATE_SOURCE_VALUE, languageValues[srcPos])
            .putString(PREF_TRANSLATE_TARGET_CODE, targetCodes[tgtPos])
            .apply()
    }

    private fun apiCodeForSourceSpinnerIndex(index: Int): String {
        val languageValues = resources.getStringArray(R.array.language_values)
        if (index !in languageValues.indices) return "kan_Knda"
        return LANGUAGE_VALUE_TO_API_CODE[languageValues[index]] ?: "kan_Knda"
    }

    private fun sourceSpinnerIndexForTargetApiCode(code: String): Int {
        val languageValues = resources.getStringArray(R.array.language_values)
        val idx = languageValues.indexOfFirst { LANGUAGE_VALUE_TO_API_CODE[it] == code }
        return if (idx >= 0) idx else 0
    }

    private fun targetSpinnerIndexForApiCode(code: String): Int {
        val targetCodes = resources.getStringArray(R.array.target_language_codes)
        val idx = targetCodes.indexOf(code)
        return if (idx >= 0) idx else 0
    }

    private fun swapSourceAndTargetLanguages() {
        val srcCode = apiCodeForSourceSpinnerIndex(sourceLanguageSpinner.selectedItemPosition)
        val targetCodes = resources.getStringArray(R.array.target_language_codes)
        val tgtPos = targetLanguageSpinner.selectedItemPosition
        if (tgtPos !in targetCodes.indices) return
        val tgtCode = targetCodes[tgtPos]
        val newSrcIdx = sourceSpinnerIndexForTargetApiCode(tgtCode)
        val newTgtIdx = targetSpinnerIndexForApiCode(srcCode)
        sourceLanguageSpinner.setSelection(newSrcIdx, false)
        targetLanguageSpinner.setSelection(newTgtIdx, false)
        saveLanguageSelections()
    }

    private fun launchGalleryPicker() {
        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun launchCamera() {
        val cameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        if (cameraPermission != PackageManager.PERMISSION_GRANTED) {
            pendingCameraAfterPermission = true
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
            return
        }
        pendingCameraAfterPermission = false
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
        updateRecyclerViewPadding()
        scrollToLatestMessage()
    }

    private fun getTranslationResponse(input: String) {
        // Removed unnecessary token check as API uses X-API-Key
        val sourceIndex = sourceLanguageSpinner.selectedItemPosition
        val languageValues = resources.getStringArray(R.array.language_values)
        val selectedLanguage = languageValues[sourceIndex]
        val srcLang = LANGUAGE_VALUE_TO_API_CODE[selectedLanguage] ?: "kan_Knda"
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
                    val sid = currentSessionId ?: return@performApiCall
                    lifecycleScope.launch(Dispatchers.IO) {
                        getSessionRepository().addMessage(sid, "Translation: $translatedText", timestamp, isQuery = false, null, null)
                    }
                },
                onError = { e -> Log.e("TranslateActivity", "Translation failed: ${e.message}", e) }
            )
        }
    }

    private fun handleImageUpload(uri: Uri, isFromCamera: Boolean) {
        val fileName = getFileName(uri)
        val query = "Extract text from image"

        var tempFile: File? = null

        try {
            if (isFromCamera && photoFile != null && photoFile!!.exists()) {
                // For camera, use the photoFile directly
                tempFile = photoFile
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
        val sessionId = currentSessionId ?: return
        val timestamp = DateUtils.getCurrentTimestamp()
        lifecycleScope.launch(Dispatchers.IO) {
            getSessionRepository().addMessageWithAttachment(
                sessionId, "Image translation...", timestamp, true, uri, fileType
            )
        }
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
        val srcLang: String = LANGUAGE_VALUE_TO_API_CODE[selectedLanguage] ?: "kan_Knda"
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
                val response = RetrofitClient.apiService(this@TranslateActivity).visualQuery(
                    filePart,
                    queryPart,
                    encryptedSrcLang,
                    encryptedTgtLang,
                    RetrofitClient.getApiKey()
                )
                val answerText = response.answer
                val timestamp = DateUtils.getCurrentTimestamp()
                val sid = currentSessionId
                if (sid != null) {
                    getSessionRepository().addMessage(
                        sid, "Translation: $answerText", timestamp, isQuery = false, null, null
                    )
                }
                runOnUiThread {
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_sessions -> {
                showSessionListBottomSheet()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSessionListBottomSheet() {
        val bottomSheet = SessionListBottomSheet.newInstance(
            sessionType = SessionType.TRANSLATE,
            onSessionSelected = { sessionId -> switchToSession(sessionId) },
            onNewSessionRequested = { createNewSession() }
        )
        bottomSheet.show(supportFragmentManager, "SessionListBottomSheet")
    }

    private fun switchToSession(sessionId: String) {
        currentSessionId = sessionId
        loadMessagesForSession(sessionId)
    }

    private fun createNewSession() {
        lifecycleScope.launch(Dispatchers.IO) {
            val newSession = getSessionRepository().createSession(SessionType.TRANSLATE, null)
            withContext(Dispatchers.Main) {
                switchToSession(newSession.id)
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
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            CAMERA_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    if (pendingCameraAfterPermission) {
                        pendingCameraAfterPermission = false
                        launchCamera()
                    }
                } else {
                    pendingCameraAfterPermission = false
                    Toast.makeText(this, "Camera permission denied. Cannot take photos.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}