package com.slabstech.dhwani.voiceai.di

import com.slabstech.dhwani.voiceai.AudioRepository
import com.slabstech.dhwani.voiceai.MainViewModel
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

val appModule =
    module {
        single {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
        single { AudioRepository(androidContext(), get()) }
        viewModel { MainViewModel(get()) } // Injects OkHttpClient into MainViewModel
    }
