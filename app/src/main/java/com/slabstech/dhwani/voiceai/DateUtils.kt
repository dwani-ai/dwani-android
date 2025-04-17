package com.slabstech.dhwani.voiceai

import java.text.SimpleDateFormat
import java.util.*

object DateUtils {
    fun getCurrentTimestamp(): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }
}