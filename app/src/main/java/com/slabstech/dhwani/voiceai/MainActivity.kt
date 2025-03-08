package com.slabstech.dhwani.voiceai

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private val recordAudioPermissionCode = 100
    private lateinit var historyRecyclerView: RecyclerView
    private lateinit var audioLevelBar: android.widget.ProgressBar
    private lateinit var progressBar: android.widget.ProgressBar
    private lateinit var pushToTalkFab: FloatingActionButton
    private lateinit var textQueryInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var toolbar: Toolbar
    private var currentTheme: Boolean? = null

    private val viewModel: MainViewModel by viewModel()
    private lateinit var messageAdapter: MessageAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val isDarkTheme = prefs.getBoolean("dark_theme", false)
        if (currentTheme == null || currentTheme != isDarkTheme) {
            setTheme(if (isDarkTheme) R.style.Theme_DhwaniVoiceAI_Dark else R.style.Theme_DhwaniVoiceAI_Light)
            currentTheme = isDarkTheme
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        historyRecyclerView = findViewById(R.id.historyRecyclerView)
        audioLevelBar = findViewById(R.id.audioLevelBar)
        progressBar = findViewById(R.id.progressBar)
        pushToTalkFab = findViewById(R.id.pushToTalkFab)
        textQueryInput = findViewById(R.id.textQueryInput)
        sendButton = findViewById(R.id.sendButton)
        toolbar = findViewById(R.id.toolbar)

        setSupportActionBar(toolbar)

        messageAdapter =
            MessageAdapter(viewModel.messages.value.orEmpty().toMutableList()) { position ->
                showMessageOptionsDialog(position)
            }
        historyRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = messageAdapter
        }

        // Observe messages LiveData
        viewModel.messages.observe(this) { messages ->
            messageAdapter.updateMessages(messages.toMutableList())
            if (toolbar.menu.findItem(R.id.action_auto_scroll)?.isChecked == true) {
                historyRecyclerView.scrollToPosition(messages.size - 1)
            }
        }

        // Observe recording state
        viewModel.isRecording.observe(this) { isRecording ->
            if (isRecording) {
                animateFabRecordingStart()
            } else {
                animateFabRecordingStop()
            }
        }

        viewModel.audioLevels.observe(this) { levels ->
            audioLevelBar.progress =
                levels.maxOrNull()?.let { (it * 100).toInt().coerceIn(0, 100) } ?: 0
        }

        viewModel.progressVisible.observe(this) { visible ->
            progressBar.visibility = if (visible) View.VISIBLE else View.GONE
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                recordAudioPermissionCode,
            )
        }
        pushToTalkFab.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    viewModel.startRecording()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    viewModel.stopRecording()
                    animateFabRecordingStop() // Ensure animation stops
                    true
                }
                else -> false
            }
        }

        sendButton.setOnClickListener {
            val query = textQueryInput.text.toString().trim()
            if (query.isNotEmpty()) {
                val timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                viewModel.sendTextQuery(query, timestamp)
                textQueryInput.text.clear()
            } else {
                Toast.makeText(this, "Please enter a query", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
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
                viewModel.clearMessages()
                true
            }

            R.id.action_repeat -> {
                // TODO: Implement repeat with ViewModel
                Toast.makeText(this, "Repeat not implemented yet", Toast.LENGTH_SHORT).show()
                true
            }

            R.id.action_share -> {
                val lastMessage = viewModel.messages.value?.lastOrNull()
                if (lastMessage != null) {
                    shareMessage(lastMessage)
                } else {
                    Toast.makeText(this, "No messages to share", Toast.LENGTH_SHORT).show()
                }
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun animateFabRecordingStart() {
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.2f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.2f)
        ObjectAnimator.ofPropertyValuesHolder(pushToTalkFab, scaleX, scaleY).apply {
            duration = 200
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            start()
        }
    }

    private fun animateFabRecordingStop() {
        pushToTalkFab.clearAnimation()
        pushToTalkFab.scaleX = 1f
        pushToTalkFab.scaleY = 1f
    }

    private fun showMessageOptionsDialog(position: Int) {
        val message = viewModel.messages.value?.get(position) ?: return
        val options = arrayOf("Copy", "Share")
        AlertDialog.Builder(this)
            .setTitle("Message Options")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> copyMessage(message)
                    1 -> shareMessage(message)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareMessage(message: Message) {
        val shareIntent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "${message.timestamp}: ${message.text}")
            }
        startActivity(Intent.createChooser(shareIntent, "Share message"))
    }

    private fun copyMessage(message: Message) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Message", "${message.timestamp}: ${message.text}")
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Message copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    class MessageAdapter(
        private val messages: MutableList<Message>,
        private val onLongClick: (Int) -> Unit,
    ) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {
        class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val messageText: TextView = itemView.findViewById(R.id.messageText)
            val timestampText: TextView = itemView.findViewById(R.id.timestampText)
            val messageContainer: LinearLayout = itemView.findViewById(R.id.messageContainer)
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): MessageViewHolder {
            val view =
                LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
            return MessageViewHolder(view)
        }

        override fun onBindViewHolder(
            holder: MessageViewHolder,
            position: Int,
        ) {
            val message = messages[position]
            holder.messageText.text = message.text
            holder.timestampText.text = message.timestamp

            // Align queries (isQuery = true) to the right, answers to the left
            val layoutParams = holder.messageContainer.layoutParams as LinearLayout.LayoutParams
            layoutParams.gravity =
                if (message.isQuery) android.view.Gravity.END else android.view.Gravity.START
            holder.messageContainer.layoutParams = layoutParams

            // Set bubble color: green for queries (right), white for answers (left)
            holder.messageContainer.isActivated =
                !message.isQuery // White for answers, green for queries

            val animation =
                AnimationUtils.loadAnimation(holder.itemView.context, android.R.anim.fade_in)
            holder.itemView.startAnimation(animation)

            holder.itemView.setOnLongClickListener {
                onLongClick(position)
                true
            }
        }

        override fun getItemCount(): Int = messages.size

        fun updateMessages(newMessages: MutableList<Message>) {
            messages.clear()
            messages.addAll(newMessages)
            notifyDataSetChanged()
        }
    }
}

data class Message(val text: String, val timestamp: String, val isQuery: Boolean)
