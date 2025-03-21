package com.slabstech.dhwani.voiceai

import android.app.Application
import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

class DhwaniApp : Application() {
    override fun onCreate() {
        super.onCreate()
        scheduleTokenRefresh()
    }

    private fun scheduleTokenRefresh() {
        val workRequest = PeriodicWorkRequestBuilder<TokenRefreshWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "token_refresh",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }
}

class TokenRefreshWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val success = AuthManager.refreshTokenIfNeeded(applicationContext)
        return if (success) Result.success() else Result.retry()
    }
}