package com.eneskocamaan.kenet.data.api

import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    // Emulator için 10.0.2.2, Gerçek cihaz için Bilgisayarın IP adresi (örn: 192.168.1.35)
    private const val BASE_URL = "http://92.249.61.192:8000/"

    private val gson = GsonBuilder()
        .setLenient() // Hatalı JSON formatlarına karşı esneklik sağlar
        .create()

    // --- LOGLAMA İÇİN CLIENT ---
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    val api: KenetApi by lazy {
        retrofit.create(KenetApi::class.java)
    }
}