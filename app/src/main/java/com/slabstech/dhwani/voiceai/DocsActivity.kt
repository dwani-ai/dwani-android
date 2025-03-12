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
import android.text.Editable
import android.util.Base64
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class DocsActivity : AppCompatActivity() {

    private val STORAGE_PERMISSION_CODE = 102
    private lateinit var historyRecyclerView: RecyclerView
    private lateinit var audioLevelBar: ProgressBar
    private lateinit var progressBar: ProgressBar
    private lateinit var attachFab: FloatingActionButton
    private lateinit var textQueryInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var toolbar: Toolbar
    private val messageList = mutableListOf<Message>()
    private lateinit var messageAdapter: MessageAdapter
    private val VLM_API_ENDPOINT = "https://gaganyatri-llm-indic-server-vlm.hf.space/v1/docs"
    private val RETRY_DELAY_MS = 2000L

    // Activity Result Launcher for photo picker or legacy picker
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val imageUri = result.data?.data
            imageUri?.let { processImage(it) }
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
            attachFab = findViewById(R.id.attachFab)
            textQueryInput = findViewById(R.id.textQueryInput)
            sendButton = findViewById(R.id.sendButton)
            toolbar = findViewById(R.id.toolbar)
            val bottomNavigation = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNavigation)

            setSupportActionBar(toolbar)

            messageAdapter = MessageAdapter(messageList, { position ->
                showMessageOptionsDialog(position)
            }, { _, _ -> })
            historyRecyclerView.apply {
                layoutManager = LinearLayoutManager(this@DocsActivity)
                adapter = messageAdapter
                visibility = View.VISIBLE
                setBackgroundColor(ContextCompat.getColor(this@DocsActivity, android.R.color.transparent))
            }

            attachFab.setOnClickListener {
                openImagePicker()
            }

            sendButton.setOnClickListener {
                val query = textQueryInput.text.toString().trim()
                if (query.isNotEmpty()) {
                    val timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    val message = Message("Text Input: $query", timestamp, true)
                    messageList.add(message)
                    messageAdapter.notifyItemInserted(messageList.size - 1)
                    historyRecyclerView.requestLayout()
                    scrollToLatestMessage()
                    textQueryInput.text.clear()
                } else {
                    Toast.makeText(this, "Please enter text or upload an image", Toast.LENGTH_SHORT).show()
                }
            }

            textQueryInput.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (s.isNullOrEmpty()) {
                        sendButton.visibility = View.GONE
                        attachFab.visibility = View.VISIBLE
                    } else {
                        sendButton.visibility = View.VISIBLE
                        attachFab.visibility = View.GONE
                    }
                }
            })

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
            // Android 14+: Use system photo picker (no permission needed)
            val intent = Intent(MediaStore.ACTION_PICK_IMAGES)
            intent.type = "image/*"
            pickImageLauncher.launch(intent)
        } else {
            // Android 13 and below: Check permission and use legacy picker
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

    private fun processImage(uri: Uri) {
        runOnUiThread { progressBar.visibility = View.VISIBLE }

        Thread {
            try {
                val inputStream: InputStream? = contentResolver.openInputStream(uri)
                val byteArrayOutputStream = ByteArrayOutputStream()
                inputStream?.use { it.copyTo(byteArrayOutputStream) }
                val imageBytes = byteArrayOutputStream.toByteArray()
                val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                val imageDataUrl = "data:image;base64,$base64Image"

                val uploadTimestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val uploadMessage = Message("Uploaded Image", uploadTimestamp, true)
                runOnUiThread {
                    messageList.add(uploadMessage)
                    messageAdapter.notifyItemInserted(messageList.size - 1)
                    historyRecyclerView.requestLayout()
                    scrollToLatestMessage()
                }

                getImageDescription(imageDataUrl)
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Image processing failed: ${e.message}", Toast.LENGTH_LONG).show()
                    progressBar.visibility = View.GONE
                }
            }
        }.start()
    }

    private fun getImageDescription(base64Image: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val maxRetries = prefs.getString("max_retries", "3")?.toIntOrNull() ?: 3
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val messages = JSONArray().put(
            JSONObject().apply {
                put("role", "user")
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "image")
                        put("image", base64Image)
                    })
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", "Describe this image.")
                    })
                })
            }
        )

        val requestBody = JSONObject().put("messages", messages).toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(VLM_API_ENDPOINT)
            .header("accept", "application/json")
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
                    val description = json.optJSONArray("choices")
                        ?.getJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content") ?: "No description available"
                    val timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    val message = Message("Description: $description", timestamp, false)
                    runOnUiThread {
                        messageList.add(message)
                        messageAdapter.notifyItemInserted(messageList.size - 1)
                        historyRecyclerView.requestLayout()
                        scrollToLatestMessage()
                        progressBar.visibility = View.GONE
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "Image description failed after $maxRetries retries", Toast.LENGTH_SHORT).show()
                        progressBar.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("DocsActivity", "VLM parsing error: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(this, "Image processing error: ${e.message}", Toast.LENGTH_LONG).show()
                    progressBar.visibility = View.GONE
                }
            }
        }.start()
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
        val options = arrayOf("Delete", "Share", "Copy")
        AlertDialog.Builder(this)
            .setTitle("Message Options")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { // Delete
                        messageList.removeAt(position)
                        messageAdapter.notifyItemRemoved(position)
                        messageAdapter.notifyItemRangeChanged(position, messageList.size)
                    }
                    1 -> { // Share
                        shareMessage(message)
                    }
                    2 -> { // Copy
                        copyMessage(message)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copyMessage(message: Message) {
        val copyText = "${message.text}\n[${message.timestamp}]"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Message", copyText)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Message copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    data class Message(
        val text: String,
        val timestamp: String,
        val isQuery: Boolean
    )

    class MessageAdapter(
        private val messages: MutableList<Message>,
        private val onLongClick: (Int) -> Unit,
        private val onAudioControlClick: (Message, ImageButton) -> Unit
    ) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

        class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val messageText: TextView = itemView.findViewById(R.id.messageText)
            val timestampText: TextView = itemView.findViewById(R.id.timestampText)
            val messageContainer: LinearLayout = itemView.findViewById(R.id.messageContainer)
            val audioControlButton: ImageButton = itemView.findViewById(R.id.audioControlButton)
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