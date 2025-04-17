package com.slabstech.dhwani.voiceai

import android.content.Context
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

object ApiUtils {
    suspend fun <T> performApiCall(
        context: Context,
        progressBar: ProgressBar,
        apiCall: suspend () -> T,
        onSuccess: (T) -> Unit,
        onError: (Exception) -> Unit
    ) {
        progressBar.visibility = View.VISIBLE
        try {
            val result = apiCall()
            onSuccess(result)
        } catch (e: Exception) {
            onError(e)
            (context as? AppCompatActivity)?.runOnUiThread {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } finally {
            progressBar.visibility = View.GONE
        }
    }
}