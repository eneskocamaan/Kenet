package com.eneskocamaan.kenet.data.api

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    // Sunucu Adresi
    private const val BASE_URL = "http://92.249.61.192:8000/"

    val api: KenetApi by lazy {
        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        // Eski SSL/TLS kodları SİLİNDİ.
        // Android 8.0+ varsayılan ayarlar yeterlidir.

        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .client(clientBuilder.build())
            .build()

        retrofit.create(KenetApi::class.java)
    }
}