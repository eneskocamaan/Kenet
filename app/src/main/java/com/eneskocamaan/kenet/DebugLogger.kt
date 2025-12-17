package com.eneskocamaan.kenet

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLogger {
    private val _logs = MutableLiveData<String>("")
    val logs: LiveData<String> = _logs

    private val logBuffer = StringBuilder()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun log(tag: String, message: String) {
        // Geliştirici Logcat'i
        Log.d(tag, message)

        // Uygulama İçi Log
        val timestamp = dateFormat.format(Date())
        val formattedLog = "[$timestamp] $tag: $message\n"

        synchronized(this) {
            logBuffer.append(formattedLog)
            _logs.postValue(logBuffer.toString())
        }
    }

    fun clear() {
        synchronized(this) {
            logBuffer.clear()
            _logs.postValue("")
        }
    }

    // Buraya da synchronized ekledim, daha güvenli.
    fun getLogText(): String {
        synchronized(this) {
            return logBuffer.toString()
        }
    }
}