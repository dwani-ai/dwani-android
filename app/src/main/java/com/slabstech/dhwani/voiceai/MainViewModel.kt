package com.slabstech.dhwani.voiceai

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(private val audioRepository: AudioRepository) : ViewModel() {
    // LiveData for message list
    private val _messages = MutableLiveData<List<Message>>(emptyList())
    val messages: LiveData<List<Message>> get() = _messages

    // LiveData for recording state
    private val _isRecording = MutableLiveData(false)
    val isRecording: LiveData<Boolean> get() = _isRecording

    private val _audioLevels = MutableLiveData<FloatArray>()
    val audioLevels: LiveData<FloatArray> get() = _audioLevels

    private val _progressVisible = MutableLiveData(false)
    val progressVisible: LiveData<Boolean> get() = _progressVisible

    // Add a message (query or response)
    fun addMessage(
        text: String,
        timestamp: String,
        isQuery: Boolean,
    ) {
        val currentList = _messages.value.orEmpty().toMutableList()
        currentList.add(Message(text, timestamp, isQuery))
        _messages.value = currentList
    }

    // Clear messages
    fun clearMessages() {
        _messages.value = emptyList()
    }

    fun startRecording() {
        viewModelScope.launch {
            _isRecording.value = true
            val rmsValues = audioRepository.startRecording()
            if (rmsValues == null) {
                _isRecording.value = false
                val timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                addMessage("Error: Recording permission denied", timestamp, false)
            } else {
                _audioLevels.value = rmsValues
            }
        }
    }

    fun stopRecording() {
        audioRepository.stopRecording()
        _isRecording.value = false
        viewModelScope.launch {
            _progressVisible.value = true
            val (text, error) = audioRepository.sendAudioToApi(audioRepository.audioFile)
            _progressVisible.value = false
            val timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            if (text != null) {
                addMessage("Voice Query: $text", timestamp, true)
                // TODO: Send to chat API (Step 4)
            } else {
                addMessage("Error: $error", timestamp, false)
            }
        }
    }

    // Placeholder for sending text query (we’ll expand this in Step 3)
    fun sendTextQuery(
        query: String,
        timestamp: String,
    ) {
        viewModelScope.launch {
            addMessage("Query: $query", timestamp, true)
            // TODO: Call API with okHttpClient (will move to repository in Step 3)
        }
    }
}
