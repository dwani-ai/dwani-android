package com.slabstech.dhwani.voiceai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView

abstract class MessageActivity : AuthenticatedActivity() {
    protected lateinit var historyRecyclerView: RecyclerView
    protected lateinit var messageAdapter: MessageAdapter
    protected val messageList = mutableListOf<Message>()

    protected fun setupMessageList() {
        messageAdapter = MessageAdapter(messageList, { position ->
            showMessageOptionsDialog(position)
        }, { message, button ->
            toggleAudioPlayback(message, button)
        })
        historyRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MessageActivity)
            adapter = messageAdapter
            visibility = View.VISIBLE
            setBackgroundColor(ContextCompat.getColor(this@MessageActivity, android.R.color.transparent))
        }
    }

    protected fun showMessageOptionsDialog(position: Int) {
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

    protected fun shareMessage(message: Message) {
        val shareText = "${message.text}\n[${message.timestamp}]"
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        startActivity(Intent.createChooser(shareIntent, "Share Message"))
    }

    protected fun copyMessage(message: Message) {
        val copyText = "${message.text}\n[${message.timestamp}]"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Message", copyText)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Message copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    protected fun scrollToLatestMessage() {
        val autoScrollEnabled = findViewById<Toolbar>(R.id.toolbar)?.menu?.findItem(R.id.action_auto_scroll)?.isChecked ?: false
        if (autoScrollEnabled && messageList.isNotEmpty()) {
            historyRecyclerView.post {
                val itemCount = messageAdapter.itemCount
                if (itemCount > 0) {
                    historyRecyclerView.smoothScrollToPosition(itemCount - 1)
                }
            }
        }
    }

    protected fun setupBottomNavigation(currentItemId: Int) {
        val bottomNavigation = findViewById<BottomNavigationView>(R.id.bottomNavigation)
        NavigationUtils.setupBottomNavigation(this, bottomNavigation, currentItemId)
    }

    abstract fun toggleAudioPlayback(message: Message, button: ImageButton)
}