package com.eneskocamaan.kenet

import android.content.Context
import java.util.UUID

// Tüm uygulama genelinde kullanılabilecek cihaz ID'si
val Context.myDeviceId: String
    get() {
        val sharedPref = getSharedPreferences("KENET_PREFS", Context.MODE_PRIVATE)
        var id = sharedPref.getString("DEVICE_ID", null)
        if (id == null) {
            id = UUID.randomUUID().toString().substring(0, 8)
            sharedPref.edit().putString("DEVICE_ID", id).apply()
        }
        return id!!
    }