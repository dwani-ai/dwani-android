package com.slabstech.dhwani.voiceai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.media.AudioRecord
import android.media.MediaPlayer
import com.slabstech.dhwani.voiceai.utils.SpeechUtils

import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

class AnswerActivity : MessageActivity() {

    private lateinit var textQueryInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var toolbar: Toolbar
    private lateinit var progressBar: ProgressBar


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge layout
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContentView(R.layout.activity_answer)

        // View initializations
        historyRecyclerView = findViewById(R.id.historyRecyclerView)
        textQueryInput = findViewById(R.id.textQueryInput)
        sendButton = findViewById(R.id.sendButton)
        toolbar = findViewById(R.id.toolbar)

        setSupportActionBar(toolbar)
        setupMessageList()
        setupBottomNavigation(R.id.nav_answer)
        setupInsets()


        // Handle Send button click
        sendButton.setOnClickListener {
            val query = textQueryInput.text.toString().trim()
            if (query.isNotEmpty()) {
                submitQuery(query)
            } else {
                Toast.makeText(this, "Please enter a query", Toast.LENGTH_SHORT).show()
            }
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
                } else {
                    sendButton.visibility = View.VISIBLE
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



    private fun getChatResponse(prompt: String) {
        val selectedLanguage = prefs.getString("language", "kannada") ?: "kannada"
        val languageMap = mapOf(
            "english" to "eng_Latn",
            "hindi" to "hin_Deva",
            "kannada" to "kan_Knda",
            "tamil" to "tam_Taml",
            "german" to "deu_Latn",
            "telugu" to "tel_Telu"
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
                },
                onError = { e -> Log.e("AnswerActivity", "Chat failed: ${e.message}", e) }
            )
        }
    }


    override fun onDestroy() {
        super.onDestroy()
    }
}