package com.yonizilber.flownics.data.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.yonizilber.flownics.BuildConfig
import com.yonizilber.flownics.data.remote.gemini.GeminiApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.create
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

private const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/"

// Claude goes through the Anthropic SDK, which builds its own HTTP client (see ClaudeAiClient).
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .apply {
                // Debug builds only, and BASIC prints method + URL, never headers: the key travels
                // in a header and so is never logged. Release builds log nothing.
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
                }
            }
            .connectTimeout(20, TimeUnit.SECONDS)
            // A web-searched, structured answer can take well over a minute.
            .readTimeout(150, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    fun provideGeminiApiService(client: OkHttpClient, json: Json): GeminiApiService =
        Retrofit.Builder()
            .baseUrl(GEMINI_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create()
}
