package com.slabstech.dhwani.voiceai

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import okhttp3.OkHttpClient

class MainViewModel(private val _okHttpClient: OkHttpClient) : ViewModel() {
    private val _messages = MutableLiveData<List<Message>>(emptyList())
    val messages: LiveData<List<Message>> get() = _messages

    val okHttpClient: OkHttpClient get() = _okHttpClient

    fun addMessage(
        text: String,
        timestamp: String,
        isQuery: Boolean,
    ) {
        val currentList = _messages.value.orEmpty().toMutableList()
        currentList.add(Message(text, timestamp, isQuery))
        _messages.value = currentList
    }

    fun clearMessages() {
        _messages.value = emptyList()
    }

    fun sendTextQuery(
        query: String,
        timestamp: String,
    ) {
        addMessage("Query: $query", timestamp, true)
    }
}
