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
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.appcompat.widget.Toolbar
import android.view.Menu
import android.view.MenuItem
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

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
    private val VLM_API_ENDPOINT = "https://gaganyatri-dhwani-server.hf.space/v1/visual_query/"

    //private val VLM_API_ENDPOINT = "http://:7860/v1/visual_query/"

    private val RETRY_DELAY_MS = 2000L
    private var lastImageUri: Uri? = null

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
                processImage(it, "describe image") // Process immediately
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val isDarkTheme = prefs.getBoolean("dark_theme", false)
        setTheme(if (isDarkTheme) R.style.Theme_DhwaniVoiceAI_Dark else R.style.Theme_DhwaniVoiceAI_Light)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_docs)

        try {
            historyRecyclerView = findViewById(R.id.historyRecyclerView)
            audioLevelBar = findViewById(R.id.audioLevelBar)
            progressBar = findViewById(R.id.progressBar)
            ttsProgressBar = findViewById(R.id.ttsProgressBar)
            attachFab = findViewById(R.id.attachFab)
            toolbar = findViewById(R.id.toolbar)
            val bottomNavigation = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNavigation)

            setSupportActionBar(toolbar)

            messageAdapter = MessageAdapter(messageList, { position ->
                showMessageOptionsDialog(position)
            }, { message, _ ->
                if (message.isQuery && message.imageUri != null) {
                    showCustomQueryDialog(message.imageUri!!)
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
            android.util.Log.e("DocsActivity", "Crash in onCreate: ${e.message}", e)
            Toast.makeText(this, "Initialization failed: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
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
        android.util.Log.d("DocsActivity", "Permission result: requestCode=$requestCode, permissions=${permissions.joinToString()}, grantResults=${grantResults.joinToString()}")
        if (requestCode == STORAGE_PERMISSION_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            android.util.Log.d("DocsActivity", "Storage permission granted")
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            pickImageLauncher.launch(intent)
        } else {
            android.util.Log.w("DocsActivity", "Storage permission denied")
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
        runOnUiThread { progressBar.visibility = View.VISIBLE }

        Thread {
            try {
                val imageFile = uriToFile(uri)
                if (imageFile == null || !imageFile.exists()) {
                    runOnUiThread {
                        Toast.makeText(this, "Failed to process image file", Toast.LENGTH_SHORT).show()
                        progressBar.visibility = View.GONE
                    }
                    return@Thread
                }

                getImageDescription(imageFile, query)
            } catch (e: Exception) {
                android.util.Log.e("DocsActivity", "Image processing error: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(this, "Image processing failed: ${e.message}", Toast.LENGTH_LONG).show()
                    progressBar.visibility = View.GONE
                }
            }
        }.start()
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
            android.util.Log.e("DocsActivity", "Failed to convert Uri to File: ${e.message}", e)
            null
        }
    }

    private fun getImageDescription(imageFile: File, query: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        val dhwaniApiKey = prefs.getString("chat_api_key", "dhwani-version-api-server-0-0-1") ?: "dhwani-version-api-server-0-0-1"

        val maxRetries = prefs.getString("max_retries", "3")?.toIntOrNull() ?: 3
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", imageFile.name,
                imageFile.asRequestBody("image/jpeg".toMediaType())
            )
            .addFormDataPart("query", query)
            .build()

        val request = Request.Builder()
            .url(VLM_API_ENDPOINT)
            .header("accept", "application/json")
            .header("X-API-Key", dhwaniApiKey)
            .post(requestBody)
            .build()

        Thread {
            try {
                var attempts = 0
                var success = false
                var responseBody: String? = null

                while (attempts < maxRetries && !success) {
                    try {
                        val response = client.newCall(request).execute()
                        if (response.isSuccessful) {
                            responseBody = response.body?.string()
                            android.util.Log.d("DocsActivity", "VLM Response: $responseBody")
                            success = true
                        } else {
                            attempts++
                            android.util.Log.w("DocsActivity", "API failed with code: ${response.code}")
                            if (attempts < maxRetries) Thread.sleep(RETRY_DELAY_MS)
                        }
                    } catch (e: Exception) {
                        attempts++
                        android.util.Log.e("DocsActivity", "Network error: ${e.message}")
                        if (attempts < maxRetries) Thread.sleep(RETRY_DELAY_MS)
                    }
                }

                if (success && responseBody != null) {
                    val json = JSONObject(responseBody)
                    val description = json.optString("answer", "No description available")
                    val timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    val message = Message("Response: $description", timestamp, false)
                    runOnUiThread {
                        messageList.add(message)
                        messageAdapter.notifyItemInserted(messageList.size - 1)
                        historyRecyclerView.requestLayout()
                        scrollToLatestMessage()
                        progressBar.visibility = View.GONE
                        if (messageList.size == 2) { // First upload
                            Toast.makeText(this, "Tap the thumbnail to ask more!", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "Image query failed after $maxRetries retries", Toast.LENGTH_SHORT).show()
                        progressBar.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("DocsActivity", "VLM parsing error: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(this, "Image query error: ${e.message}", Toast.LENGTH_LONG).show()
                    progressBar.visibility = View.GONE
                }
            } finally {
                imageFile.delete()
            }
        }.start()
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

    data class Message(
        val text: String,
        val timestamp: String,
        val isQuery: Boolean,
        val imageUri: Uri? = null
    )

    class MessageAdapter(
        private val messages: MutableList<Message>,
        private val onLongClick: (Int) -> Unit,
        private val onThumbnailClick: (Message, ImageButton) -> Unit // Changed to onThumbnailClick
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
                    onThumbnailClick(message, holder.audioControlButton) // Trigger custom query
                }
            } else {
                holder.thumbnailImage.visibility = View.GONE
            }

            holder.audioControlButton.visibility = View.GONE

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