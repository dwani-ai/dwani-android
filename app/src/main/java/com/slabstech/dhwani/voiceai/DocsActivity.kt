package com.slabstech.dhwani.voiceai

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
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
import android.util.Log.e
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.slabstech.dhwani.voiceai.repository.SessionRepository
import com.slabstech.dhwani.voiceai.repository.SessionType
import com.slabstech.dhwani.voiceai.utils.SpeechUtils
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

class DocsActivity : AppCompatActivity() {

    private val READ_STORAGE_PERMISSION_CODE = 101
    private val CAMERA_PERMISSION_CODE = 102
    private lateinit var historyRecyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var ttsProgressBar: ProgressBar
    private lateinit var attachFab: FloatingActionButton
    private lateinit var toolbar: Toolbar
    private val messageList = mutableListOf<Message>()
    private lateinit var messageAdapter: MessageAdapter
    private var currentTheme: Boolean? = null
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private var currentSessionId: String? = null
    private var messageCollectionJob: kotlinx.coroutines.Job? = null
    private var emptyStateView: View? = null

    private val sessionRepository: SessionRepository
        get() = (application as DhwaniApp).sessionRepository

    private fun updateEmptyState() {
        emptyStateView?.visibility = if (messageList.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun loadMessagesForSession(sessionId: String) {
        messageCollectionJob?.cancel()
        messageCollectionJob = lifecycleScope.launch {
            sessionRepository.getMessages(sessionId).collectLatest { list ->
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

    // List of allowed languages
    private val ALLOWED_LANGUAGES = listOf(
        "Assamese", "Bengali", "Gujarati", "Hindi", "Kannada",
        "Malayalam", "Marathi", "Odia", "Punjabi", "Tamil",
        "Telugu", "English", "German"
    )

    private var selectedFileType: String? = null

    // Launcher for non-visual files (PDF, Audio) using traditional GetContent
    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleFileUpload(it, selectedFileType, false) }
    }

    // Launcher for images using Photo Picker (Android 13+ recommended, falls back on older)
    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { handleFileUpload(it, "image", false) }
    }

    // Launcher for camera capture
    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            currentPhotoUri?.let { uri -> handleFileUpload(uri, "image", true) }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        val isDarkTheme = prefs.getBoolean("dark_theme", false)
        setTheme(if (isDarkTheme) R.style.Theme_DhwaniVoiceAI_Dark else R.style.Theme_DhwaniVoiceAI_Light)
        currentTheme = isDarkTheme
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Optional: style the system bars to match your theme
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.isAppearanceLightStatusBars = !isDarkTheme
        windowInsetsController?.isAppearanceLightNavigationBars = !isDarkTheme

        setContentView(R.layout.activity_docs)

        try {
            historyRecyclerView = findViewById(R.id.historyRecyclerView)
            progressBar = findViewById(R.id.progressBar)
            ttsProgressBar = findViewById(R.id.ttsProgressBar)
            attachFab = findViewById(R.id.attachFab)
            toolbar = findViewById(R.id.toolbar)
            val bottomNavigation = findViewById<BottomNavigationView>(R.id.bottomNavigation)

            // Handle insets manually (top and bottom padding)
            ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.coordinatorLayout)) { view, insets ->
                val systemInsets = insets.getInsets(    WindowInsetsCompat.Type.systemBars())
                view.setPadding(0, systemInsets.top, 0, systemInsets.bottom)
                insets
            }

            setSupportActionBar(toolbar)
            emptyStateView = findViewById(R.id.emptyStateView)

            messageAdapter = MessageAdapter(messageList, { position ->
                showMessageOptionsDialog(position)
            }, { _, _ -> }) // No audio playback in DocsActivity

            historyRecyclerView.apply {
                layoutManager = LinearLayoutManager(this@DocsActivity)
                adapter = messageAdapter
                visibility = View.VISIBLE
                setBackgroundColor(ContextCompat.getColor(this@DocsActivity, android.R.color.transparent))
            }

            // Get session ID from Intent or create/get current session
            val intentSessionId = intent.getStringExtra("SESSION_ID")
            lifecycleScope.launch {
                val session = withContext(Dispatchers.IO) {
                    if (intentSessionId != null) {
                        sessionRepository.getSession(intentSessionId)
                            ?: sessionRepository.getOrCreateCurrentSession(SessionType.DOCS)
                    } else {
                        sessionRepository.getOrCreateCurrentSession(SessionType.DOCS)
                    }
                }
                withContext(Dispatchers.Main) {
                    currentSessionId = session.id
                    loadMessagesForSession(session.id)
                }
            }

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

            findViewById<View>(R.id.toolbarSubtitle)?.setOnClickListener { showSessionListBottomSheet() }

            attachFab.setOnClickListener {
                showFileTypeSelectionDialog()
            }

            bottomNavigation.setOnItemSelectedListener { item ->
                when (item.itemId) {
                    R.id.nav_answer -> {
                        AlertDialog.Builder(this)
                            .setMessage("Switch to Answer?")
                            .setPositiveButton("Yes") { _, _ ->
                                startActivity(Intent(this, AnswerActivity::class.java))
                            }
                            .setNegativeButton("No", null)
                            .show()
                        false
                    }
                    R.id.nav_assistant -> {
                        AlertDialog.Builder(this)
                            .setMessage("Switch to Assistant?")
                            .setPositiveButton("Yes") { _, _ ->
                                startActivity(Intent(this, VoiceAssistantActivity::class.java))
                            }
                            .setNegativeButton("No", null)
                            .show()
                        false
                    }
                    R.id.nav_voice -> {
                        AlertDialog.Builder(this)
                            .setMessage("Switch to Voice?")
                            .setPositiveButton("Yes") { _, _ ->
                                startActivity(Intent(this, VoiceDetectionActivity::class.java))
                            }
                            .setNegativeButton("No", null)
                            .show()
                        false
                    }
                    R.id.nav_docs -> true
                    else -> false
                }
            }

            bottomNavigation.selectedItemId = R.id.nav_docs
        } catch (e: Exception) {
            Log.e("DocsActivity", "Crash in onCreate: ${e.message}", e)
            Toast.makeText(this, "Initialization failed: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun showFileTypeSelectionDialog() {
        val options = arrayOf("Camera", "Gallery", "PDF", "Audio")
        AlertDialog.Builder(this)
            .setTitle("Select File Type")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        selectedFileType = "image"
                        launchCamera()
                    }
                    1 -> {
                        selectedFileType = "image"
                        // Use Photo Picker for images (permissionless on Android 13+)
                        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    2 -> {
                        selectedFileType = "pdf"
                        pickFileLauncher.launch("application/pdf")
                    }
                    3 -> {
                        selectedFileType = "audio"
                        pickFileLauncher.launch("audio/*")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
            Log.e("DocsActivity", "Failed to create temp image file: ${e.message}", e)
            Toast.makeText(this, "Failed to prepare camera", Toast.LENGTH_SHORT).show()
            null
        }
    }

    override fun onResume() {
        super.onResume()
        val isDarkTheme = prefs.getBoolean("dark_theme", false)
        if (currentTheme != isDarkTheme) {
            currentTheme = isDarkTheme
            recreate()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        menu.findItem(R.id.action_auto_scroll)?.isChecked = true
        menu.findItem(R.id.action_target_language)?.isVisible = false
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_auto_scroll -> {
                item.isChecked = !item.isChecked
                true
            }
            R.id.action_clear -> {
                val sid = currentSessionId
                if (sid != null) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        sessionRepository.deleteAllMessagesInSession(sid)
                    }
                } else {
                    messageList.clear()
                    messageAdapter.notifyDataSetChanged()
                    updateEmptyState()
                }
                true
            }
            R.id.action_sessions -> {
                showSessionListBottomSheet()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSessionListBottomSheet() {
        val bottomSheet = SessionListBottomSheet.newInstance(
            sessionType = SessionType.DOCS,
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
            val newSession = sessionRepository.createSession(SessionType.DOCS, null)
            withContext(Dispatchers.Main) {
                switchToSession(newSession.id)
            }
        }
    }

    private fun showMessageOptionsDialog(position: Int) {
        if (position < 0 || position >= messageList.size) return
        val message = messageList[position]
        val options = arrayOf("Delete", "Share", "Copy")
        AlertDialog.Builder(this)
            .setTitle("Message Options")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        if (message.id != null && currentSessionId != null) {
                            lifecycleScope.launch(Dispatchers.IO) {
                                sessionRepository.deleteMessage(message.id!!)
                            }
                        } else {
                            messageList.removeAt(position)
                            messageAdapter.notifyItemRemoved(position)
                            messageAdapter.notifyItemRangeChanged(position, messageList.size)
                        }
                    }
                    1 -> shareMessage(message)
                    2 -> copyMessage(message)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareMessage(message: Message) {
        val shareText = "${message.text}\n[${message.timestamp}]"
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        startActivity(Intent.createChooser(shareIntent, "Share Message"))
    }

    private fun copyMessage(message: Message) {
        val copyText = "${message.text}\n[${message.timestamp}]"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = ClipData.newPlainText("Message", copyText)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Message copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun validateLanguage(language: String): String {
        val languageMap = ALLOWED_LANGUAGES.associateBy { it.lowercase() }
        val lowerCaseLanguage = language.lowercase()
        if (lowerCaseLanguage !in languageMap) {
            throw IllegalArgumentException(
                "Unsupported language: $language. Supported languages: $ALLOWED_LANGUAGES"
            )
        }
        return lowerCaseLanguage
    }

    private fun validateLanguageImage(language: String): String {
        val languageMap = ALLOWED_LANGUAGES.associateBy { it.lowercase() }
        val lowerCaseLanguage = language.lowercase()
        if (lowerCaseLanguage !in languageMap) {
            throw IllegalArgumentException(
                "Unsupported language: $language. Supported languages: $ALLOWED_LANGUAGES"
            )
        }
        return lowerCaseLanguage
    }

    private fun handleFileUpload(uri: Uri, fileType: String?, isFromCamera: Boolean) {
        val fileName = getFileName(uri)
        var query = "Describe the content"

        var inputFile: File? = null

        try {
            if (isFromCamera) {
                inputFile = photoFile
            } else {
                // For gallery/non-camera, copy from inputStream
                val inputStream = contentResolver.openInputStream(uri)
                inputFile = File(cacheDir, fileName ?: "temp_file.${fileType}")

                inputStream?.use { input ->
                    FileOutputStream(inputFile!!).use { output ->
                        input.copyTo(output)
                    }
                } ?: run {
                    Log.e("DocsActivity", "InputStream is null for URI: $uri")
                    Toast.makeText(this, "Failed to read the selected file. URI scheme: ${uri.scheme}, authority: ${uri.authority}", Toast.LENGTH_LONG).show()
                    return
                }
            }

            // Check if file was successfully created and has content
            if (inputFile == null || !inputFile!!.exists() || inputFile!!.length() == 0L) {
                Log.e("DocsActivity", "Input file invalid: exists=${inputFile?.exists()}, size=${inputFile?.length()}")
                Toast.makeText(this, "Failed to read the selected file.", Toast.LENGTH_SHORT).show()
                return
            }

            when (fileType) {
                "image" -> {
                    if (!isFromCamera && !isImageFile(fileName ?: "")) {
                        Toast.makeText(this, "Please select an image file", Toast.LENGTH_SHORT).show()
                        return
                    }
                    query = "Describe the image"
                    val compressedFile = compressImage(inputFile!!)
                    inputFile!!.delete() // Delete original after compression
                    if (isFromCamera) {
                        photoFile = null
                    }
                    processFileUpload(compressedFile, uri, query, "image/png", false, "image")
                }
                "pdf" -> {
                    if (!fileName.lowercase().endsWith(".pdf")) {
                        Toast.makeText(this, "Please select a PDF file", Toast.LENGTH_SHORT).show()
                        return
                    }
                    query = "Summarize the PDF content"
                    processFileUpload(inputFile!!, uri, query, "application/pdf", true, "pdf")
                }
                "audio" -> {
                    if (!isAudioFile(fileName)) {
                        Toast.makeText(this, "Please select an audio file", Toast.LENGTH_SHORT).show()
                        return
                    }
                    query = "Transcribe the audio"
                    processFileUpload(inputFile!!, uri, query, "audio/mpeg", false, "audio")
                }
                else -> {
                    Toast.makeText(this, "Invalid file type", Toast.LENGTH_SHORT).show()
                    return
                }
            }
        } catch (e: Exception) {
            Log.e("DocsActivity", "File processing failed: ${e.message}", e)
            Toast.makeText(this, "File processing failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun isImageFile(fileName: String): Boolean {
        val lowerCaseName = fileName.lowercase()
        return lowerCaseName.endsWith(".jpg") ||
                lowerCaseName.endsWith(".jpeg") ||
                lowerCaseName.endsWith(".png")
    }

    private fun isAudioFile(fileName: String): Boolean {
        val lowerCaseName = fileName.lowercase()
        return lowerCaseName.endsWith(".mp3") ||
                lowerCaseName.endsWith(".wav") ||
                lowerCaseName.endsWith(".m4a")
    }

    private fun processFileUpload(file: File, uri: Uri, query: String, mediaType: String, isPdf: Boolean, fileType: String) {
        val sessionId = currentSessionId ?: return
        val timestamp = DateUtils.getCurrentTimestamp()
        lifecycleScope.launch(Dispatchers.IO) {
            sessionRepository.addMessageWithAttachment(
                sessionId, query, timestamp, true, uri, fileType
            )
        }
        if (isPdf) {
            getPdfSummaryResponse(file, uri, fileType)
        } else if (mediaType == "audio/mpeg") {
            getTranscriptionResponse(file, uri, fileType)
        } else {
            getVisualQueryResponse(query, file, mediaType, fileType)
        }
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

    private fun scrollToLatestMessage() {
        val autoScrollEnabled = toolbar.menu.findItem(R.id.action_auto_scroll)?.isChecked ?: false
        if (autoScrollEnabled && messageList.isNotEmpty()) {
            historyRecyclerView.post {
                val itemCount = messageAdapter.itemCount
                if (itemCount > 0) {
                    historyRecyclerView.smoothScrollToPosition(itemCount - 1)
                }
            }
        }
    }

    private fun getTranscriptionResponse(file: File, uri: Uri, fileType: String) {
        val selectedLanguage = prefs.getString("language", "kannada") ?: "kannada"
        val apiLanguage = try {
            validateLanguage(selectedLanguage)
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            runOnUiThread { progressBar.visibility = View.VISIBLE }
            try {
                // Encrypt audio if necessary (currently a no-op in RetrofitClient)
                val audioBytes = file.readBytes()
                val encryptedAudio = RetrofitClient.encryptAudio(audioBytes)
                val tempFile = File(cacheDir, "encrypted_${file.name}")
                tempFile.writeBytes(encryptedAudio)

                // Prepare multipart request
                val requestFile = tempFile.asRequestBody("audio/mpeg".toMediaType())
                val filePart = MultipartBody.Part.createFormData("file", file.name, requestFile)

                // Call the transcription API
                val response = RetrofitClient.apiService(this@DocsActivity).transcribeAudio(
                    audio = filePart,
                    language = apiLanguage,
                    apiKey = RetrofitClient.getApiKey()
                )

                // Process response
                val transcriptionText = response.text
                if (transcriptionText.isBlank()) {
                    throw Exception("Empty transcription received")
                }

                val timestamp = DateUtils.getCurrentTimestamp()
                val sid = currentSessionId
                if (sid != null) {
                    val chatMsg = sessionRepository.addMessageWithAttachment(
                        sid, "Transcription: $transcriptionText", timestamp, false, uri, fileType
                    )
                    val message = Message(
                        text = chatMsg.text,
                        timestamp = chatMsg.timestamp,
                        isQuery = false,
                        uri = uri,
                        fileType = fileType,
                        id = chatMsg.id
                    )
                    runOnUiThread {
                        scrollToLatestMessage()

                        // Optional: Convert transcription to speech
                        SpeechUtils.textToSpeech(
                            context = this@DocsActivity,
                            scope = lifecycleScope,
                            text = transcriptionText,
                            message = message,
                            recyclerView = historyRecyclerView,
                            adapter = messageAdapter,
                            ttsProgressBarVisibility = { visible ->
                                ttsProgressBar.visibility = if (visible) View.VISIBLE else View.GONE
                            },
                            srcLang = apiLanguage
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("DocsActivity", "Transcription failed: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(
                        this@DocsActivity,
                        "Transcription error: ${e.message ?: "Unknown error"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                runOnUiThread { progressBar.visibility = View.GONE }
                file.delete()
                //if (tempFile.exists()) tempFile.delete()
            }
        }
    }

    private fun getVisualQueryResponse(query: String, file: File, mediaType: String, fileType: String) {
        val selectedLanguage = prefs.getString("language", "kannada") ?: "kannada"

        val languageMap = mapOf(
            "english" to "eng_Latn",
            "hindi" to "hin_Deva",
            "kannada" to "kan_Knda",
            "tamil" to "tam_Taml",
            "german" to "deu_Latn",
            "telugu" to "tel_Telu"
        )
        val srcLang: String = languageMap[selectedLanguage] ?: "kan_Knda"
        val tgtLang = srcLang

        lifecycleScope.launch(Dispatchers.IO) {
            runOnUiThread { progressBar.visibility = View.VISIBLE }
            try {
                val requestFile = file.asRequestBody(mediaType.toMediaType())
                val filePart = MultipartBody.Part.createFormData("file", file.name, requestFile)
                val queryPart = query.toRequestBody("text/plain".toMediaType())
                val response = RetrofitClient.apiService(this@DocsActivity).visualQuery(
                    filePart,
                    queryPart,
                    srcLang,
                    tgtLang,
                    RetrofitClient.getApiKey()
                )
                val answerText = response.answer
                val timestamp = DateUtils.getCurrentTimestamp()
                val sid = currentSessionId
                if (sid != null) {
                    val chatMsg = sessionRepository.addMessage(
                        sid, "Answer: $answerText", timestamp, isQuery = false, null, null
                    )
                    val message = Message(
                        text = chatMsg.text,
                        timestamp = chatMsg.timestamp,
                        isQuery = false,
                        uri = null,
                        fileType = null,
                        id = chatMsg.id
                    )
                    runOnUiThread {
                        scrollToLatestMessage()

                        SpeechUtils.textToSpeech(
                            context = this@DocsActivity,
                            scope = lifecycleScope,
                            text = answerText,
                            message = message,
                            recyclerView = historyRecyclerView,
                            adapter = messageAdapter,
                            ttsProgressBarVisibility = { visible ->
                                ttsProgressBar.visibility = if (visible) View.VISIBLE else View.GONE
                            },
                            srcLang = tgtLang
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("DocsActivity", "Query failed: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(this@DocsActivity, "Query error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                runOnUiThread { progressBar.visibility = View.GONE }
                file.delete()
            }
        }
    }

    private fun getPdfSummaryResponse(file: File, uri: Uri, fileType: String) {
        val selectedLanguage = prefs.getString("language", "kannada") ?: "kannada"
        val validSrcLang = try {
            validateLanguage(selectedLanguage)
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            return
        }

        val languageMap = mapOf(
            "english" to "eng_Latn",
            "hindi" to "hin_Deva",
            "kannada" to "kan_Knda",
            "tamil" to "tam_Taml",
            "german" to "deu_Latn",
            "telugu" to "tel_Telu"
        )
        val srcLang: String = languageMap[validSrcLang] ?: "kan_Knda"
        val tgtLang = srcLang
        val pageNumber = "1" // Summarize only the first page
        val model = "gemma3"

        lifecycleScope.launch(Dispatchers.IO) {
            runOnUiThread { progressBar.visibility = View.VISIBLE }
            try {
                val requestFile = file.asRequestBody("application/pdf".toMediaType())
                val filePart = MultipartBody.Part.createFormData("file", file.name, requestFile)
                val tgtLangPart = tgtLang.toRequestBody("text/plain".toMediaType())
                val modelPart = model.toRequestBody("text/plain".toMediaType())

                val response = RetrofitClient.apiService(this@DocsActivity).summarizePdf(
                    filePart,
                    tgtLang = tgtLangPart,
                    model = modelPart,
                    apiKey = RetrofitClient.getApiKey()
                )
                val summaryText = response.translated_summary
                val timestamp = DateUtils.getCurrentTimestamp()
                val sid = currentSessionId
                if (sid != null) {
                    val chatMsg = sessionRepository.addMessage(
                        sid, "Summary: $summaryText", timestamp, isQuery = false, null, fileType
                    )
                    val message = Message(
                        text = chatMsg.text,
                        timestamp = chatMsg.timestamp,
                        isQuery = false,
                        uri = uri,
                        fileType = fileType,
                        id = chatMsg.id
                    )
                    runOnUiThread {
                        scrollToLatestMessage()

                        SpeechUtils.textToSpeech(
                            context = this@DocsActivity,
                            scope = lifecycleScope,
                            text = summaryText,
                            message = message,
                            recyclerView = historyRecyclerView,
                            adapter = messageAdapter,
                            ttsProgressBarVisibility = { visible ->
                                ttsProgressBar.visibility = if (visible) View.VISIBLE else View.GONE
                            },
                            srcLang = tgtLang
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("DocsActivity", "PDF summary failed: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(this@DocsActivity, "PDF summary error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                runOnUiThread { progressBar.visibility = View.GONE }
                file.delete()
            }
        }
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
            READ_STORAGE_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    showFileTypeSelectionDialog()
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