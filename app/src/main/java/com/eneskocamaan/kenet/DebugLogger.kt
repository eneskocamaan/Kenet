package com.eneskocamaan.kenet

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object DebugLogger {
    // Thread-safe liste (Aynı anda hem yazıp hem okurken çökmemesi için)
    private val logs = CopyOnWriteArrayList<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun log(tag: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val logEntry = "[$timestamp] [$tag]: $message"

        // Logcat'e de yazsın (Android Studio'dan takip için)
        android.util.Log.d("KENET_DEBUG", logEntry)

        // Ekrana basmak için listeye ekle
        logs.add(0, logEntry) // En yeniyi en başa ekle

        // Hafıza şişmesin diye 500 logda sınırla
        if (logs.size > 500) {
            logs.removeAt(logs.size - 1)
        }
    }

    fun getLogText(): String {
        return logs.joinToString("\n----------------\n")
    }

    fun clear() {
        logs.clear()
    }
}