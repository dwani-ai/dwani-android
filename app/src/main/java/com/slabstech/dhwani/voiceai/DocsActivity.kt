package com.slabstech.dhwani.voiceai

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.appcompat.widget.Toolbar
import android.view.Menu
import android.view.MenuItem
import java.text.SimpleDateFormat
import java.util.*
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import com.slabstech.dhwani.voiceai.utils.SpeechUtils
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream

class DocsActivity : AppCompatActivity() {

    private val READ_STORAGE_PERMISSION_CODE = 101
    private lateinit var historyRecyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var ttsProgressBar: ProgressBar
    private lateinit var attachFab: FloatingActionButton
    private lateinit var toolbar: Toolbar
    private val messageList = mutableListOf<Message>()
    private lateinit var messageAdapter: MessageAdapter
    private var currentTheme: Boolean? = null
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    private var selectedFileType: String? = null

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleFileUpload(it, selectedFileType) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val isDarkTheme = prefs.getBoolean("dark_theme", false)
        setTheme(if (isDarkTheme) R.style.Theme_DhwaniVoiceAI_Dark else R.style.Theme_DhwaniVoiceAI_Light)
        currentTheme = isDarkTheme

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_docs)

        try {
            historyRecyclerView = findViewById(R.id.historyRecyclerView)
            progressBar = findViewById(R.id.progressBar)
            ttsProgressBar = findViewById(R.id.ttsProgressBar)
            attachFab = findViewById(R.id.attachFab)
            toolbar = findViewById(R.id.toolbar)
            val bottomNavigation = findViewById<BottomNavigationView>(R.id.bottomNavigation)

            setSupportActionBar(toolbar)

            messageAdapter = MessageAdapter(messageList, { position ->
                showMessageOptionsDialog(position)
            }, { _, _ -> }) // No audio playback in DocsActivity
            historyRecyclerView.apply {
                layoutManager = LinearLayoutManager(this@DocsActivity)
                adapter = messageAdapter
                visibility = View.VISIBLE
                setBackgroundColor(ContextCompat.getColor(this@DocsActivity, android.R.color.transparent))
            }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    READ_STORAGE_PERMISSION_CODE
                )
            }

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
                    R.id.nav_vision -> {
                        AlertDialog.Builder(this)
                            .setMessage("Switch to Vision?")
                            .setPositiveButton("Yes") { _, _ ->
                                startActivity(Intent(this, VisionActivity::class.java))
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
        val options = arrayOf("Image", "PDF", "Audio")
        AlertDialog.Builder(this)
            .setTitle("Select File Type")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        selectedFileType = "image"
                        pickFileLauncher.launch("image/*")
                    }
                    1 -> {
                        selectedFileType = "pdf"
                        pickFileLauncher.launch("application/pdf")
                    }
                    2 -> {
                        selectedFileType = "audio"
                        pickFileLauncher.launch("audio/*")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
                messageList.clear()
                messageAdapter.notifyDataSetChanged()
                true
            }
            else -> super.onOptionsItemSelected(item)
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
                        messageList.removeAt(position)
                        messageAdapter.notifyItemRemoved(position)
                        messageAdapter.notifyItemRangeChanged(position, messageList.size)
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

    private fun encryptFile(file: ByteArray): ByteArray {
        return RetrofitClient.encryptAudio(file) // Reusing audio encryption (returns plain data)
    }

    private fun handleFileUpload(uri: Uri, fileType: String?) {
        val fileName = getFileName(uri)
        val inputStream = contentResolver.openInputStream(uri)
        val file = File(cacheDir, fileName)
        var query = "Describe the content"

        try {
            inputStream?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }

            when (fileType) {
                "image" -> {
                    if (!isImageFile(fileName)) {
                        Toast.makeText(this, "Please select an image file", Toast.LENGTH_SHORT).show()
                        return
                    }
                    val compressedFile = compressImage(file)
                    query = "Describe the image"
                    processFileUpload(compressedFile, uri, query, "image/png")
                }
                "pdf" -> {
                    if (!fileName.lowercase().endsWith(".pdf")) {
                        Toast.makeText(this, "Please select a PDF file", Toast.LENGTH_SHORT).show()
                        return
                    }
                    query = "Summarize the PDF content"
                    processFileUpload(file, uri, query, "application/pdf")
                }
                "audio" -> {
                    if (!isAudioFile(fileName)) {
                        Toast.makeText(this, "Please select an audio file", Toast.LENGTH_SHORT).show()
                        return
                    }
                    query = "Transcribe the audio"
                    processFileUpload(file, uri, query, "audio/mpeg")
                }
                else -> {
                    Toast.makeText(this, "Invalid file type", Toast.LENGTH_SHORT).show()
                    return
                }
            }
        } catch (e: Exception) {
            Log.e("DocsActivity", "File processing failed: ${e.message}", e)
            Toast.makeText(this, "File processing failed: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            inputStream?.close()
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

    private fun processFileUpload(file: File, uri: Uri, query: String, mediaType: String) {
        val timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val message = Message(query, timestamp, true, uri)
        messageList.add(message)
        messageAdapter.notifyItemInserted(messageList.size - 1)
        historyRecyclerView.requestLayout()
        scrollToLatestMessage()
        getVisualQueryResponse(query, file, mediaType)
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

    private fun getVisualQueryResponse(query: String, file: File, mediaType: String) {
        val selectedLanguage = prefs.getString("language", "kannada") ?: "kannada"

        val languageMap = mapOf(
            "english" to "eng_Latn",
            "hindi" to "hin_Deva",
            "kannada" to "kan_Knda",
            "tamil" to "tam_Taml",
            "german" to "deu_Latn"
        )

        val srcLang = languageMap[selectedLanguage] ?: "kan_Knda"
        val tgtLang = srcLang

        lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            try {
                val requestFile = file.asRequestBody(mediaType.toMediaType())
                val filePart = MultipartBody.Part.createFormData("file", file.name, requestFile)
                val queryPart = query.toRequestBody("text/plain".toMediaType())
                Log.d("DocsActivity", "File part - name: ${file.name}, size: ${file.length()}, type: $mediaType")
                Log.d("DocsActivity", "Query: $query, src_lang: $srcLang, tgt_lang: $tgtLang")
                val response = RetrofitClient.apiService(this@DocsActivity).visualQuery(
                    filePart,
                    queryPart,
                    srcLang,
                    tgtLang,
                    RetrofitClient.getApiKey()
                )
                val answerText = response.answer
                val timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val message = Message("Answer: $answerText", timestamp, false)
                messageList.add(message)
                messageAdapter.notifyItemInserted(messageList.size - 1)
                historyRecyclerView.requestLayout()
                scrollToLatestMessage()

                SpeechUtils.textToSpeech(
                    context = this@DocsActivity,
                    scope = lifecycleScope,
                    text = answerText,
                    message = message,
                    recyclerView = historyRecyclerView,
                    adapter = messageAdapter,
                    ttsProgressBarVisibility = { visible -> ttsProgressBar.visibility = if (visible) View.VISIBLE else View.GONE },
                    srcLang = tgtLang
                )
            } catch (e: Exception) {
                Log.e("DocsActivity", "Query failed: ${e.message}", e)
                Toast.makeText(this@DocsActivity, "Query error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                progressBar.visibility = View.GONE
                file.delete()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == READ_STORAGE_PERMISSION_CODE && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            showFileTypeSelectionDialog()
        }
    }
}