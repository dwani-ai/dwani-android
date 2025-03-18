package com.slabstech.dhwani.voiceai

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.appcompat.widget.Toolbar
import android.view.Menu
import android.view.MenuItem
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

class DocsActivity : AppCompatActivity() {

    private val STORAGE_PERMISSION_CODE = 102
    private lateinit var historyRecyclerView: RecyclerView
    private lateinit var audioLevelBar: ProgressBar
    private lateinit var progressBar: ProgressBar
    private lateinit var ttsProgressBar: ProgressBar
    private lateinit var attachFab: FloatingActionButton
    private lateinit var toolbar: Toolbar
    private val messageList = mutableListOf<Message>()
    private lateinit var messageAdapter: MessageAdapter
    private var lastImageUri: Uri? = null
    private var mediaPlayer: MediaPlayer? = null // For TTS playback
    private val AUTO_PLAY_KEY = "auto_play_tts"
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val imageUri = result.data?.data
            imageUri?.let {
                lastImageUri = it
                val timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val message = Message("Uploaded Image", timestamp, true, it)
                messageList.add(message)
                messageAdapter.notifyItemInserted(messageList.size - 1)
                historyRecyclerView.requestLayout()
                scrollToLatestMessage()
                processImage(it, "describe image")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val isDarkTheme = prefs.getBoolean("dark_theme", false)
        setTheme(if (isDarkTheme) R.style.Theme_DhwaniVoiceAI_Dark else R.style.Theme_DhwaniVoiceAI_Light)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_docs)

        fetchAccessToken()

        try {
            historyRecyclerView = findViewById(R.id.historyRecyclerView)
            audioLevelBar = findViewById(R.id.audioLevelBar)
            progressBar = findViewById(R.id.progressBar)
            ttsProgressBar = findViewById(R.id.ttsProgressBar)
            attachFab = findViewById(R.id.attachFab)
            toolbar = findViewById(R.id.toolbar)
            val bottomNavigation = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNavigation)

            setSupportActionBar(toolbar)

            // Initialize TTS settings if not present
            if (!prefs.contains(AUTO_PLAY_KEY)) {
                prefs.edit().putBoolean(AUTO_PLAY_KEY, true).apply()
            }
            if (!prefs.contains("tts_enabled")) {
                prefs.edit().putBoolean("tts_enabled", false).apply()
            }

            messageAdapter = MessageAdapter(messageList, { position ->
                showMessageOptionsDialog(position)
            }, { message, button ->
                if (message.isQuery && message.imageUri != null) {
                    showCustomQueryDialog(message.imageUri!!)
                } else if (!message.isQuery && message.audioFile != null) {
                    toggleAudioPlayback(message, button)
                }
            })
            historyRecyclerView.apply {
                layoutManager = LinearLayoutManager(this@DocsActivity)
                adapter = messageAdapter
                visibility = View.VISIBLE
                setBackgroundColor(ContextCompat.getColor(this@DocsActivity, android.R.color.transparent))
            }

            attachFab.setOnClickListener {
                openImagePicker()
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
                    R.id.nav_translate -> {
                        AlertDialog.Builder(this)
                            .setMessage("Switch to Translate?")
                            .setPositiveButton("Yes") { _, _ ->
                                startActivity(Intent(this, TranslateActivity::class.java))
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

    private fun fetchAccessToken() {
        lifecycleScope.launch {
            try {
                val response = RetrofitClient.apiService.login(LoginRequest("testuser", "password123"))
                prefs.edit().putString("access_token", response.access_token).apply()
                Log.d("DocsActivity", "Token fetched: ${response.access_token}")
            } catch (e: Exception) {
                Log.e("DocsActivity", "Token fetch failed: ${e.message}", e)
                Toast.makeText(this@DocsActivity, "Authentication failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openImagePicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val intent = Intent(MediaStore.ACTION_PICK_IMAGES)
            intent.type = "image/*"
            pickImageLauncher.launch(intent)
        } else {
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                requestStoragePermission(permission)
            } else {
                val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                pickImageLauncher.launch(intent)
            }
        }
    }

    private fun requestStoragePermission(permission: String) {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
            AlertDialog.Builder(this)
                .setTitle("Storage Permission Needed")
                .setMessage("This app needs storage access to upload images. Please grant the permission.")
                .setPositiveButton("OK") { _, _ ->
                    ActivityCompat.requestPermissions(this, arrayOf(permission), STORAGE_PERMISSION_CODE)
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(permission), STORAGE_PERMISSION_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            pickImageLauncher.launch(intent)
        } else {
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            if (!ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
                AlertDialog.Builder(this)
                    .setTitle("Permission Denied")
                    .setMessage("Storage permission was permanently denied. Please enable it in Settings > Apps > Dhwani Voice AI > Permissions.")
                    .setPositiveButton("Go to Settings") { _, _ ->
                        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        intent.data = Uri.parse("package:$packageName")
                        startActivity(intent)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                Toast.makeText(this, "Storage permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun processImage(uri: Uri, query: String) {
        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val imageFile = uriToFile(uri)
                if (imageFile == null || !imageFile.exists()) {
                    Toast.makeText(this@DocsActivity, "Failed to process image file", Toast.LENGTH_SHORT).show()
                    progressBar.visibility = View.GONE
                    return@launch
                }

                getImageDescriptionAndTranslation(imageFile, query)
            } catch (e: Exception) {
                Log.e("DocsActivity", "Image processing error: ${e.message}", e)
                Toast.makeText(this@DocsActivity, "Image processing failed: ${e.message}", Toast.LENGTH_LONG).show()
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun uriToFile(uri: Uri): File? {
        return try {
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            val tempFile = File(cacheDir, "temp_image_${System.currentTimeMillis()}.jpg")
            FileOutputStream(tempFile).use { outputStream ->
                inputStream?.use { it.copyTo(outputStream) }
            }
            tempFile
        } catch (e: Exception) {
            Log.e("DocsActivity", "Failed to convert Uri to File: ${e.message}", e)
            null
        }
    }

    private fun getImageDescriptionAndTranslation(imageFile: File, query: String) {
        val token = prefs.getString("access_token", null) ?: return
        val languageMap = mapOf(
            "english" to "eng_Latn",
            "hindi" to "hin_Deva",
            "kannada" to "kan_Knda",
            "tamil" to "tam_Taml",
            "malayalam" to "mal_Mlym",
            "telugu" to "tel_Telu"
        )
        val selectedLanguage = prefs.getString("language", "kannada") ?: "kannada"
        val tgtLang = languageMap[selectedLanguage] ?: "kan_Knda"

        val requestFile = imageFile.asRequestBody("image/jpeg".toMediaType())
        val filePart = MultipartBody.Part.createFormData("file", imageFile.name, requestFile)
        val queryPart = query.toRequestBody("text/plain".toMediaType())

        lifecycleScope.launch {
            try {
                // Step 1: Get English description
                val vlmResponse = RetrofitClient.apiService.visualQuery(filePart, queryPart, "Bearer $token")
                val englishDescription = vlmResponse.answer
                val timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val englishMessage = Message("Response (English): $englishDescription", timestamp, false)
                messageList.add(englishMessage)
                messageAdapter.notifyItemInserted(messageList.size - 1)
                historyRecyclerView.requestLayout()
                scrollToLatestMessage()
                textToSpeech(englishDescription, englishMessage) // TTS for English

                // Step 2: Translate to target language
                val translationRequest = TranslationRequest(listOf(englishDescription), "eng_Latn", tgtLang)
                val translationResponse = RetrofitClient.apiService.translate(translationRequest, "Bearer $token")
                val translatedText = translationResponse.translations.joinToString("\n")
                val translatedMessage = Message("Translation ($selectedLanguage): $translatedText", timestamp, false)
                messageList.add(translatedMessage)
                messageAdapter.notifyItemInserted(messageList.size - 1)
                historyRecyclerView.requestLayout()
                scrollToLatestMessage()
                textToSpeech(translatedText, translatedMessage) // TTS for translation

                if (messageList.size == 2) { // First upload
                    Toast.makeText(this@DocsActivity, "Tap the thumbnail to ask more!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("DocsActivity", "Processing failed: ${e.message}", e)
                Toast.makeText(this@DocsActivity, "Image query or translation error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                progressBar.visibility = View.GONE
                imageFile.delete()
            }
        }
    }

    private fun textToSpeech(text: String, message: Message) {
        if (!prefs.getBoolean("tts_enabled", false)) return
        val token = prefs.getString("access_token", null) ?: return
        val voice = prefs.getString("tts_voice", "Anu speaks with a high pitch at a normal pace in a clear, close-sounding environment. Her neutral tone is captured with excellent audio quality.")
            ?: "Anu speaks with a high pitch at a normal pace in a clear, close-sounding environment. Her neutral tone is captured with excellent audio quality."
        val autoPlay = prefs.getBoolean(AUTO_PLAY_KEY, true)

        val ttsRequest = TTSRequest(text, voice, "ai4bharat/indic-parler-tts", "mp3", 1.0)

        lifecycleScope.launch {
            ttsProgressBar.visibility = View.VISIBLE
            try {
                val response = RetrofitClient.apiService.textToSpeech(ttsRequest, "Bearer $token")
                val audioBytes = response.byteStream().readBytes()
                if (audioBytes.isNotEmpty()) {
                    val audioFile = File(cacheDir, "temp_tts_audio_${System.currentTimeMillis()}.mp3")
                    FileOutputStream(audioFile).use { fos -> fos.write(audioBytes) }
                    if (audioFile.exists() && audioFile.length() > 0) {
                        message.audioFile = audioFile
                        val messageIndex = messageList.indexOf(message)
                        if (messageIndex != -1) {
                            messageAdapter.notifyItemChanged(messageIndex)
                        }
                        if (autoPlay) {
                            playAudio(audioFile)
                        }
                    } else {
                        Toast.makeText(this@DocsActivity, "Audio file creation failed", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this@DocsActivity, "TTS returned empty audio", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("DocsActivity", "TTS failed: ${e.message}", e)
                Toast.makeText(this@DocsActivity, "TTS error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                ttsProgressBar.visibility = View.GONE
            }
        }
    }

    private fun toggleAudioPlayback(message: Message, button: ImageButton) {
        message.audioFile?.let { audioFile ->
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = null
                button.setImageResource(android.R.drawable.ic_media_play)
            } else {
                playAudio(audioFile)
                button.setImageResource(R.drawable.ic_media_stop)
            }
        }
    }

    private fun playAudio(audioFile: File) {
        if (!audioFile.exists()) {
            Toast.makeText(this, "Audio file doesn't exist", Toast.LENGTH_SHORT).show()
            return
        }

        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(audioFile.absolutePath)
            prepare()
            start()
            val messageIndex = messageList.indexOfFirst { it.audioFile == audioFile }
            if (messageIndex != -1) {
                val holder = historyRecyclerView.findViewHolderForAdapterPosition(messageIndex) as? MessageAdapter.MessageViewHolder
                holder?.audioControlButton?.setImageResource(R.drawable.ic_media_stop)
            }
            setOnCompletionListener {
                it.release()
                mediaPlayer = null
                if (messageIndex != -1) {
                    val holder = historyRecyclerView.findViewHolderForAdapterPosition(messageIndex) as? MessageAdapter.MessageViewHolder
                    holder?.audioControlButton?.setImageResource(android.R.drawable.ic_media_play)
                }
            }
            setOnErrorListener { _, what, extra ->
                Toast.makeText(this@DocsActivity, "MediaPlayer error: what=$what, extra=$extra", Toast.LENGTH_LONG).show()
                true
            }
        }
    }

    private fun showCustomQueryDialog(imageUri: Uri) {
        val editText = EditText(this)
        editText.hint = "Enter your question"
        AlertDialog.Builder(this)
            .setTitle("Ask About This Image")
            .setView(editText)
            .setPositiveButton("Ask") { _, _ ->
                val query = editText.text.toString().trim()
                if (query.isNotEmpty()) {
                    processImage(imageUri, query)
                } else {
                    Toast.makeText(this, "Please enter a question", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        menu.findItem(R.id.action_auto_scroll)?.isChecked = true
        val historyItem = menu.add(Menu.NONE, 1001, Menu.NONE, "History")
        historyItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
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
                messageList.clear()
                lastImageUri = null
                messageAdapter.notifyDataSetChanged()
                true
            }
            1001 -> {
                showHistoryDialog()
                true
            }
            R.id.action_share -> {
                if (messageList.isNotEmpty()) {
                    shareMessage(messageList.last())
                } else {
                    Toast.makeText(this, "No messages to share", Toast.LENGTH_SHORT).show()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showHistoryDialog() {
        Toast.makeText(this, "History not available without session persistence", Toast.LENGTH_SHORT).show()
    }

    private fun shareMessage(message: Message) {
        val shareText = "${message.text}\n[${message.timestamp}]"
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        startActivity(Intent.createChooser(shareIntent, "Share Message"))
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

    private fun showMessageOptionsDialog(position: Int) {
        if (position < 0 || position >= messageList.size) return

        val message = messageList[position]
        val options = arrayOf("Delete", "Share", "Copy") + if (message.isQuery && message.imageUri != null) arrayOf("Ask Another Question") else emptyArray()
        AlertDialog.Builder(this)
            .setTitle("Message Options")
            .setItems(options) { _, which ->
                when (options[which]) {
                    "Delete" -> {
                        messageList.removeAt(position)
                        messageAdapter.notifyItemRemoved(position)
                        messageAdapter.notifyItemRangeChanged(position, messageList.size)
                    }
                    "Share" -> shareMessage(message)
                    "Copy" -> copyMessage(message)
                    "Ask Another Question" -> showCustomQueryDialog(message.imageUri!!)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copyMessage(message: Message) {
        val shareText = "${message.text}\n[${message.timestamp}]"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Message", shareText)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Message copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
        messageList.forEach { it.audioFile?.delete() }
    }

    data class Message(
        val text: String,
        val timestamp: String,
        val isQuery: Boolean,
        val imageUri: Uri? = null,
        var audioFile: File? = null // Added for TTS
    )

    class MessageAdapter(
        private val messages: MutableList<Message>,
        private val onLongClick: (Int) -> Unit,
        private val onClick: (Message, ImageButton) -> Unit // Handles both thumbnail and audio clicks
    ) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

        class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val messageText: TextView = itemView.findViewById(R.id.messageText)
            val timestampText: TextView = itemView.findViewById(R.id.timestampText)
            val messageContainer: LinearLayout = itemView.findViewById(R.id.messageContainer)
            val audioControlButton: ImageButton = itemView.findViewById(R.id.audioControlButton)
            val thumbnailImage: ImageView = itemView.findViewById(R.id.thumbnailImage)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
            return MessageViewHolder(view)
        }

        override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
            val message = messages[position]
            holder.messageText.text = message.text
            holder.timestampText.text = message.timestamp

            val layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams.gravity = if (message.isQuery) android.view.Gravity.END else android.view.Gravity.START
            holder.messageContainer.layoutParams = layoutParams

            val backgroundDrawable = ContextCompat.getDrawable(holder.itemView.context, R.drawable.whatsapp_message_bubble)?.mutate()
            backgroundDrawable?.let {
                it.setTint(ContextCompat.getColor(
                    holder.itemView.context,
                    if (message.isQuery) R.color.whatsapp_message_out else R.color.whatsapp_message_in
                ))
                holder.messageText.background = it
                holder.messageText.elevation = 2f
                holder.messageText.setPadding(16, 16, 16, 16)
            }

            holder.messageText.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.whatsapp_text))
            holder.timestampText.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.whatsapp_timestamp))

            if (message.isQuery && message.imageUri != null) {
                holder.thumbnailImage.visibility = View.VISIBLE
                holder.thumbnailImage.setImageURI(message.imageUri)
                holder.thumbnailImage.setOnClickListener {
                    onClick(message, holder.audioControlButton)
                }
                holder.audioControlButton.visibility = View.GONE
            } else if (!message.isQuery && message.audioFile != null) {
                holder.thumbnailImage.visibility = View.GONE
                holder.audioControlButton.visibility = View.VISIBLE
                holder.audioControlButton.setOnClickListener {
                    onClick(message, holder.audioControlButton)
                }
                holder.audioControlButton.setColorFilter(ContextCompat.getColor(holder.itemView.context, R.color.whatsapp_green))
            } else {
                holder.thumbnailImage.visibility = View.GONE
                holder.audioControlButton.visibility = View.GONE
            }

            val animation = AnimationUtils.loadAnimation(holder.itemView.context, android.R.anim.fade_in)
            holder.itemView.startAnimation(animation)

            holder.itemView.setOnLongClickListener {
                onLongClick(position)
                true
            }
        }

        override fun getItemCount(): Int = messages.size
    }
}