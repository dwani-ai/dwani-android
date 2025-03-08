package com.slabstech.dhwani.voiceai

import android.app.Application
import com.slabstech.dhwani.voiceai.di.appModule // We'll define this next
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class DhwaniApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@DhwaniApplication) // Provides Android context to Koin
            modules(appModule) // Load the app module with dependencies
        }
    }
}
