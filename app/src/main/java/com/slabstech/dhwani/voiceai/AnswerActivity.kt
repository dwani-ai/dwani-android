package com.slabstech.dhwani.voiceai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioRecord
import android.media.MediaPlayer
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import com.slabstech.dhwani.voiceai.utils.SpeechUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.math.*

class AnswerActivity : MessageActivity() {

    private val RECORD_AUDIO_PERMISSION_CODE = 100
    private val CAMERA_PERMISSION_CODE = 102
    private var audioRecord: AudioRecord? = null
    private lateinit var audioLevelBar: ProgressBar
    private lateinit var progressBar: ProgressBar
    private lateinit var pushToTalkFab: FloatingActionButton
    private lateinit var textQueryInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var cameraButton: ImageButton
    private lateinit var toolbar: Toolbar
    private lateinit var ttsProgressBar: ProgressBar
    private var isRecording = false
    private var mediaPlayer: MediaPlayer? = null
    private val AUTO_PLAY_KEY = "auto_play_tts"

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge layout
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContentView(R.layout.activity_answer)

        // View initializations
        historyRecyclerView = findViewById(R.id.historyRecyclerView)
        audioLevelBar = findViewById(R.id.audioLevelBar)
        progressBar = findViewById(R.id.progressBar)
        pushToTalkFab = findViewById(R.id.pushToTalkFab)
        textQueryInput = findViewById(R.id.textQueryInput)
        sendButton = findViewById(R.id.sendButton)
        cameraButton = findViewById(R.id.cameraButton)
        toolbar = findViewById(R.id.toolbar)
        ttsProgressBar = findViewById(R.id.ttsProgressBar)

        setSupportActionBar(toolbar)
        setupMessageList()
        setupBottomNavigation(R.id.nav_answer)
        setupInsets()

        // Preferences initialization
        if (!prefs.contains(AUTO_PLAY_KEY)) {
            prefs.edit().putBoolean(AUTO_PLAY_KEY, true).apply()
        }
        if (!prefs.contains("tts_enabled")) {
            prefs.edit().putBoolean("tts_enabled", false).apply()
        }

        // Audio permission check
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                RECORD_AUDIO_PERMISSION_CODE
            )
        }

        // Camera permission check
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE
            )
        }

        // Push to Talk Record Toggle
        pushToTalkFab.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRecording()
                    animateFabRecordingStart()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    stopRecording()
                    animateFabRecordingStop()
                    true
                }
                else -> false
            }
        }

        // Handle Send button click
        sendButton.setOnClickListener {
            val query = textQueryInput.text.toString().trim()
            if (query.isNotEmpty()) {
                submitQuery(query)
            } else {
                Toast.makeText(this, "Please enter a query", Toast.LENGTH_SHORT).show()
            }
        }

        // Handle camera button click
        cameraButton.setOnClickListener {
            launchCamera()
        }

        // Handle "Send" action from keyboard
        textQueryInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                val query = textQueryInput.text.toString().trim()
                if (query.isNotEmpty()) {
                    submitQuery(query)
                    true
                } else {
                    Toast.makeText(this, "Please enter a query", Toast.LENGTH_SHORT).show()
                    false
                }
            } else {
                false
            }
        }

        // Scroll RecyclerView and show keyboard when EditText gains focus
        textQueryInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                updateRecyclerViewPadding() // Ensure padding is updated for keyboard
                scrollToLatestMessage()
                showKeyboard()
            }
        }

        // Toggle between TTS and Send button
        textQueryInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                if (s.isNullOrEmpty()) {
                    sendButton.visibility = View.GONE
                    pushToTalkFab.visibility = View.VISIBLE
                } else {
                    sendButton.visibility = View.VISIBLE
                    pushToTalkFab.visibility = View.GONE
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun showKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(textQueryInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun setupInsets() {
        val rootView = findViewById<View>(R.id.coordinatorLayout)
        val bottomBar = findViewById<View>(R.id.bottomBar)
        val bottomNav = findViewById<View>(R.id.bottomNavigation)

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, 0) // Top padding for status bar
            Log.d("AnswerActivity", "RootView insets applied: top=${systemBars.top}")
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(bottomBar) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.updatePadding(bottom = imeInsets.bottom + systemBars.bottom + 8) // Buffer for spacing
            Log.d("AnswerActivity", "BottomBar padding updated: bottom=${imeInsets.bottom + systemBars.bottom + 8}")
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(bottomNav) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = systemBars.bottom)
            Log.d("AnswerActivity", "BottomNav padding updated: bottom=${systemBars.bottom}")
            insets
        }
    }

    private fun submitQuery(query: String) {
        val timestamp = DateUtils.getCurrentTimestamp()
        val message = Message("Query: $query", timestamp, true, null, null)
        messageList.add(message)
        messageAdapter.notifyItemInserted(messageList.size - 1)
        Log.d("AnswerActivity", "Message added, scrolling to position: ${messageList.size - 1}")
        scrollToLatestMessage()
        getChatResponse(query)
        textQueryInput.text.clear()
        // Hide keyboard after sending
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(textQueryInput.windowToken, 0)
    }

    override fun onResume() {
        super.onResume()
        Log.d("AnswerActivity", "onResume called")
        updateRecyclerViewPadding() // Refresh padding on resume
        scrollToLatestMessage() // Ensure latest message is visible
    }

    private fun startRecording() {
        AudioUtils.startPushToTalkRecording(this, audioLevelBar, { animateFabRecordingStart() }) { file ->
            file?.let {
                val uri = Uri.fromFile(it)
                sendAudioToApi(it, uri)
            }
        }
    }

    private fun stopRecording() {
        AudioUtils.stopRecording(audioRecord, isRecording)
        animateFabRecordingStop()
    }

    private fun sendAudioToApi(audioFile: File, audioUri: Uri) {
        val selectedLanguage = prefs.getString("language", "kannada") ?: "kannada"
        val language = when (selectedLanguage.lowercase()) {
            "hindi", "tamil", "english", "german", "telugu" , "malayalam" -> selectedLanguage.lowercase()
            else -> "kannada"
        }

        val requestFile = audioFile.asRequestBody("audio/mpeg".toMediaType())
        val filePart = MultipartBody.Part.createFormData("file", audioFile.name, requestFile)

        lifecycleScope.launch {
            ApiUtils.performApiCall(
                context = this@AnswerActivity,
                progressBar = progressBar,
                apiCall = {
                    RetrofitClient.apiService(this@AnswerActivity).transcribeAudio(
                        filePart,
                        language,
                        RetrofitClient.getApiKey()
                    )
                },
                onSuccess = { response ->
                    val voiceQueryText = response.text
                    val timestamp = DateUtils.getCurrentTimestamp()

                    if (voiceQueryText.isNotEmpty()) {
                        val message = Message("Voice Query: $voiceQueryText", timestamp, true, audioUri, "audio")
                        messageList.add(message)
                        messageAdapter.notifyItemInserted(messageList.size - 1)
                        Log.d("AnswerActivity", "Voice message added, scrolling to position: ${messageList.size - 1}")
                        scrollToLatestMessage()
                        getChatResponse(voiceQueryText)
                    } else {
                        Toast.makeText(this@AnswerActivity, "Voice query empty", Toast.LENGTH_SHORT).show()
                    }

                    audioFile.delete()
                },
                onError = { e ->
                    Log.e("AnswerActivity", "Transcription failed: ${e.message}", e)
                    audioFile.delete()
                }
            )
        }
    }

    private fun getChatResponse(prompt: String) {
        val selectedLanguage = prefs.getString("language", "kannada") ?: "kannada"
        val languageMap = mapOf(
            "english" to "eng_Latn",
            "hindi" to "hin_Deva",
            "kannada" to "kan_Knda",
            "tamil" to "tam_Taml",
            "german" to "deu_Latn",
            "telugu" to "tel_Telu",
            "malayalam" to "mal_Mlym"
        )
        val langCode = languageMap[selectedLanguage] ?: "kan_Knda"

        val request = ChatRequest(prompt, langCode, langCode)

        lifecycleScope.launch {
            ApiUtils.performApiCall(
                context = this@AnswerActivity,
                progressBar = progressBar,
                apiCall = {
                    RetrofitClient.apiService(this@AnswerActivity).chat(
                        request,
                        RetrofitClient.getApiKey()
                    )
                },
                onSuccess = { response ->
                    val answerText = response.response
                    val timestamp = DateUtils.getCurrentTimestamp()
                    val message = Message("Answer: $answerText", timestamp, false, null, null)
                    messageList.add(message)
                    messageAdapter.notifyItemInserted(messageList.size - 1)
                    Log.d("AnswerActivity", "Chat response added, scrolling to position: ${messageList.size - 1}")
                    scrollToLatestMessage()
                    SpeechUtils.textToSpeech(
                        context = this@AnswerActivity,
                        scope = lifecycleScope,
                        text = answerText,
                        message = message,
                        recyclerView = historyRecyclerView,
                        adapter = messageAdapter,
                        ttsProgressBarVisibility = { visible ->
                            ttsProgressBar.visibility = if (visible) View.VISIBLE else View.GONE
                        },
                        srcLang = langCode
                    )
                },
                onError = { e -> Log.e("AnswerActivity", "Chat failed: ${e.message}", e) }
            )
        }
    }

    private fun getVisualQueryResponse(query: String, file: File, fileType: String) {
        val selectedLanguage = prefs.getString("language", "kannada") ?: "kannada"
        val languageMap = mapOf(
            "english" to "eng_Latn",
            "hindi" to "hin_Deva",
            "kannada" to "kan_Knda",
            "tamil" to "tam_Taml",
            "german" to "deu_Latn",
            "telugu" to "tel_Telu",
            "malayalam" to "mal_Mlym"
        )
        val langCode = languageMap[selectedLanguage] ?: "kan_Knda"
        val srcLang = langCode
        val tgtLang = langCode

        val encryptedQuery = RetrofitClient.encryptText(query)
        val encryptedSrcLang = RetrofitClient.encryptText(srcLang)
        val encryptedTgtLang = RetrofitClient.encryptText(tgtLang)

        lifecycleScope.launch(Dispatchers.IO) {
            runOnUiThread { progressBar.visibility = View.VISIBLE }
            try {
                val requestFile = file.asRequestBody("image/png".toMediaType())
                val filePart = MultipartBody.Part.createFormData("file", file.name, requestFile)
                val queryPart = encryptedQuery.toRequestBody("text/plain".toMediaType())
                Log.d("AnswerActivity", "File part - name: ${file.name}, size: ${file.length()}")
                Log.d("AnswerActivity", "Encrypted Query: $encryptedQuery, src_lang: $encryptedSrcLang, tgt_lang: $encryptedTgtLang")
                val response = RetrofitClient.apiService(this@AnswerActivity).visualQuery(
                    filePart,
                    queryPart,
                    encryptedSrcLang,
                    encryptedTgtLang,
                    RetrofitClient.getApiKey()
                )
                val answerText = response.answer
                val timestamp = DateUtils.getCurrentTimestamp()
                val message = Message("Answer: $answerText", timestamp, false, null, null)
                runOnUiThread {
                    messageList.add(message)
                    messageAdapter.notifyItemInserted(messageList.size - 1)
                    scrollToLatestMessage()
                }
            } catch (e: Exception) {
                Log.e("AnswerActivity", "Image analysis failed: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(this@AnswerActivity, "Image analysis error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                runOnUiThread { progressBar.visibility = View.GONE }
                if (file.exists()) {
                    file.delete()
                }
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
            Log.e("AnswerActivity", "Failed to create temp image file: ${e.message}", e)
            Toast.makeText(this, "Failed to prepare camera", Toast.LENGTH_SHORT).show()
            null
        }
    }

    private fun handleImageUpload(uri: Uri, isFromCamera: Boolean) {
        Log.d("AnswerActivity", "Handling image upload for URI: $uri, fromCamera: $isFromCamera")
        val query = textQueryInput.text.toString().trim()
        if (query.isEmpty()) {
            Toast.makeText(this, "Please enter a query for the image", Toast.LENGTH_SHORT).show()
            return
        }

        var tempFile: File? = null

        try {
            if (isFromCamera && photoFile != null && photoFile!!.exists()) {
                // For camera, use the photoFile directly
                tempFile = photoFile
                Log.d("AnswerActivity", "Using camera photo file: ${tempFile!!.absolutePath}, size: ${tempFile!!.length()}")
            } else {
                // Fallback for non-camera (not used currently)
                Log.w("AnswerActivity", "Non-camera image upload not implemented")
                return
            }

            // Check if file was successfully created and has content
            if (tempFile == null || !tempFile!!.exists() || tempFile!!.length() == 0L) {
                Log.e("AnswerActivity", "Temp file invalid: exists=${tempFile?.exists()}, size=${tempFile?.length()}")
                Toast.makeText(this, "Failed to read the captured image.", Toast.LENGTH_SHORT).show()
                return
            }

            val fileName = photoFile?.name ?: "temp_image.jpg"
            if (!isImageFile(fileName)) {
                Toast.makeText(this, "Invalid image file", Toast.LENGTH_SHORT).show()
                return
            }

            val compressedFile = compressImage(tempFile!!)
            processImageUpload(compressedFile, uri, query, "image")

            // Clean up original temp file for camera after successful processing
            if (isFromCamera) {
                photoFile = null
            }
        } catch (e: Exception) {
            Log.e("AnswerActivity", "File processing failed: ${e.message}", e)
            Toast.makeText(this, "File processing failed: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            if (!isFromCamera) {
                tempFile?.delete() // Clean up non-camera temp file if not camera
            }
        }
    }

    private fun processImageUpload(file: File, uri: Uri, query: String, fileType: String) {
        val timestamp = DateUtils.getCurrentTimestamp()
        val message = Message("Query: $query (with image)", timestamp, true, uri, fileType)
        messageList.add(message)
        messageAdapter.notifyItemInserted(messageList.size - 1)
        Log.d("AnswerActivity", "Image message added, scrolling to position: ${messageList.size - 1}")
        scrollToLatestMessage()
        textQueryInput.text.clear()
        // Hide keyboard after sending
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(textQueryInput.windowToken, 0)
        getVisualQueryResponse(query, file, fileType)
    }

    private fun isImageFile(fileName: String): Boolean {
        val lowerCaseName = fileName.lowercase()
        return lowerCaseName.endsWith(".jpg") ||
                lowerCaseName.endsWith(".jpeg") ||
                lowerCaseName.endsWith(".png") ||
                lowerCaseName.endsWith(".heic")
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
            Log.d("AnswerActivity", "Compressed file: ${outputFile.absolutePath}, size: ${outputFile.length()}")
            return outputFile
        } catch (e: Exception) {
            Log.e("AnswerActivity", "Image compression failed: ${e.message}", e)
            throw e
        }
    }

    override fun toggleAudioPlayback(message: Message, button: ImageButton) {
        mediaPlayer = SpeechUtils.toggleAudioPlayback(
            context = this,
            message = message,
            button = button,
            recyclerView = historyRecyclerView,
            adapter = messageAdapter,
            mediaPlayer = mediaPlayer,
            playIconResId = android.R.drawable.ic_media_play,
            stopIconResId = R.drawable.ic_media_stop
        )
    }

    private fun animateFabRecordingStart() {
        pushToTalkFab.setImageResource(android.R.drawable.ic_media_pause)
        val scaleUp = ObjectAnimator.ofPropertyValuesHolder(
            pushToTalkFab,
            PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.2f),
            PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.2f)
        ).apply {
            duration = 200
            start()
        }
        pushToTalkFab.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_red_light)
    }

    private fun animateFabRecordingStop() {
        pushToTalkFab.setImageResource(R.drawable.ic_mic)
        val scaleDown = ObjectAnimator.ofPropertyValuesHolder(
            pushToTalkFab,
            PropertyValuesHolder.ofFloat("scaleX", 1.2f, 1.0f),
            PropertyValuesHolder.ofFloat("scaleY", 1.2f, 1.0f)
        ).apply {
            duration = 200
            start()
        }
        pushToTalkFab.backgroundTintList = ContextCompat.getColorStateList(this, R.color.whatsapp_green)
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
        audioRecord?.release()
        audioRecord = null
        // Final cleanup for any lingering camera temp file
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
            RECORD_AUDIO_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted, audio recording available
                } else {
                    Toast.makeText(this, "Audio permission denied. Cannot record voice.", Toast.LENGTH_SHORT).show()
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