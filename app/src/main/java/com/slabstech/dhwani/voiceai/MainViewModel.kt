package com.slabstech.dhwani.voiceai

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class MainViewModel(private val okHttpClient: OkHttpClient) : ViewModel() {
    // LiveData for message list
    private val _messages = MutableLiveData<List<Message>>(emptyList())
    val messages: LiveData<List<Message>> get() = _messages

    // LiveData for recording state
    private val _isRecording = MutableLiveData(false)
    val isRecording: LiveData<Boolean> get() = _isRecording

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

    // Toggle recording state (placeholder for now)
    fun setRecordingState(isRecording: Boolean) {
        _isRecording.value = isRecording
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
