package com.slabstech.dhwani.voiceai

import android.app.Application
import com.slabstech.dhwani.voiceai.di.appModule // We'll create this next
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class DhwaniApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@DhwaniApplication)
            modules(appModule)
        }
    }
}
