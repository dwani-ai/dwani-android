package com.slabstech.dhwani.voiceai

object StringUtils {
    fun isValidBase64(str: String): Boolean {
        return str.matches(Regex("^[A-Za-z0-9+/=]+$"))
    }
}