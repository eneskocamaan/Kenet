package com.eneskocamaan.kenet.emergency

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.eneskocamaan.kenet.R
import com.eneskocamaan.kenet.data.db.AppDatabase
import com.eneskocamaan.kenet.network.BleEmergencyManager
import com.eneskocamaan.kenet.network.WifiDirectEmergencyManager
import com.eneskocamaan.kenet.service.MovementSensorService
import kotlinx.coroutines.*

class EmergencyService : Service() {
    private val TAG = "KENET_SOS_SERVICE"
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var flashlightJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var cameraManager: CameraManager

    private lateinit var bleManager: BleEmergencyManager
    private lateinit var wifiManager: WifiDirectEmergencyManager
    private lateinit var database: AppDatabase

    companion object {
        var isBroadcasting = false
        var isFlashlightOn = false
        var isWhistleOn = false
        const val ACTION_START_BROADCAST = "START_BROADCAST"
        const val ACTION_STOP_BROADCAST = "STOP_BROADCAST"
        const val ACTION_TOGGLE_FLASHLIGHT = "TOGGLE_FLASHLIGHT"
        const val ACTION_TOGGLE_WHISTLE = "TOGGLE_WHISTLE"
        const val ACTION_START_LISTENING_ONLY = "START_LISTENING_ONLY"
        const val EXTRA_MODE = "BROADCAST_MODE"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "🟢 EmergencyService Oluşturuluyor...")
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        createNotificationChannel()
        startDataCleanupLoop()

        database = AppDatabase.getDatabase(this)
        bleManager = BleEmergencyManager(this, database)
        wifiManager = WifiDirectEmergencyManager(this, database)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "📩 Komut Alındı: ${intent?.action}")
        when (intent?.action) {
            ACTION_START_BROADCAST -> {
                val mode = intent.getStringExtra(EXTRA_MODE) ?: "BLE"
                startEmergencyBroadcast(mode)
            }
            ACTION_START_LISTENING_ONLY -> startListeningOnly()
            ACTION_STOP_BROADCAST -> stopEmergencyBroadcast()
            ACTION_TOGGLE_FLASHLIGHT -> toggleFlashlight()
            ACTION_TOGGLE_WHISTLE -> toggleWhistle()
        }
        return START_STICKY
    }

    // --- DÜZELTİLEN KISIM ---
    private fun startListeningOnly() {
        // Zaten çalışıyorsa tekrar bildirim gösterme
        if (isBroadcasting) return

        Log.i(TAG, "🎧 Sadece Dinleme (Listening) Modu Aktif Ediliyor...")

        // HATA ÇÖZÜMÜ: startForegroundService ile başlatıldığı için bildirim şart!
        startForegroundService("Acil Durum Taraması Aktif", "Etraftaki sinyaller taranıyor...")

        bleManager.startScanning()
        wifiManager.startDiscovery()
    }
    // ------------------------

    private fun startEmergencyBroadcast(mode: String) {
        if (isBroadcasting) {
            Log.w(TAG, "⚠️ Zaten yayındayız, işlem tekrar edilmedi.")
            return
        }

        Log.i(TAG, "🚀 ACİL DURUM MODU BAŞLATILIYOR! Seçilen Mod: $mode")

        serviceScope.launch {
            val user = database.userDao().getUserProfile()
            if (user == null) {
                Log.e(TAG, "❌ HATA: UserEntity tablosu BOŞ! Yayın yapılamıyor.")
                return@launch
            }

            isBroadcasting = true
            startService(Intent(this@EmergencyService, MovementSensorService::class.java))
            Log.d(TAG, "👤 Kullanıcı: ${user.displayName} (${user.userId})")

            bleManager.startScanning()
            wifiManager.startDiscovery()

            if (mode == "BLE") {
                bleManager.startAdvertising()
                startForegroundService("BLE SOS Modu", "Bluetooth ile yayındasınız...")
            } else {
                wifiManager.startBroadcasting()
                startForegroundService("Wi-Fi SOS Modu", "Wi-Fi Direct ile yayındasınız...")
            }
        }
    }

    private fun stopEmergencyBroadcast() {
        Log.i(TAG, "🛑 Acil Durum Modu Durduruluyor...")
        isBroadcasting = false
        bleManager.stopAdvertising()
        bleManager.stopScanning()
        wifiManager.stopBroadcasting()
        wifiManager.stopDiscovery()
        stopService(Intent(this, MovementSensorService::class.java))
        checkStopSelf()
    }

    private fun toggleFlashlight() {
        isFlashlightOn = !isFlashlightOn
        if (isFlashlightOn) {
            startForegroundService("Fener Açık", "SOS Feneri çalışıyor")
            startSosFlashlight()
        } else {
            flashlightJob?.cancel()
            turnOffFlash()
            checkStopSelf()
        }
    }

    private fun toggleWhistle() {
        isWhistleOn = !isWhistleOn
        if (isWhistleOn) {
            startForegroundService("Düdük Açık", "Yüksek sesli uyarı")
            playWhistleSound()
        } else {
            stopWhistleSound()
            checkStopSelf()
        }
    }

    private fun startSosFlashlight() {
        flashlightJob?.cancel()
        flashlightJob = serviceScope.launch {
            val morseCode = listOf(200L, 200L, 200L, 600L, 600L, 600L, 200L, 200L, 200L)
            try {
                while (isActive && isFlashlightOn) {
                    for (duration in morseCode) {
                        setTorch(true); delay(duration)
                        setTorch(false); delay(200)
                    }
                    delay(1000)
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun setTorch(enabled: Boolean) {
        try {
            val id = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(id, enabled)
        } catch (e: Exception) {
            isFlashlightOn = false
        }
    }

    private fun turnOffFlash() { setTorch(false) }

    private fun playWhistleSound() {
        if (mediaPlayer == null) {
            try {
                mediaPlayer = MediaPlayer.create(this, R.raw.whistle_sound)
                mediaPlayer?.isLooping = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        mediaPlayer?.start()
    }

    private fun stopWhistleSound() {
        if(mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
            mediaPlayer?.seekTo(0)
        }
    }

    private fun startForegroundService(title: String, content: String) {
        val notification = NotificationCompat.Builder(this, "KENET_EMERGENCY")
            .setContentTitle(title).setContentText(content).setSmallIcon(R.drawable.ic_warning)
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_HIGH).build()
        startForeground(1, notification)
    }

    private fun checkStopSelf() {
        if (!isBroadcasting && !isFlashlightOn && !isWhistleOn) {
            Log.i(TAG, "👋 Servis tamamen kapatılıyor.")
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("KENET_EMERGENCY", "Acil Durum", NotificationManager.IMPORTANCE_HIGH)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startDataCleanupLoop() {
        serviceScope.launch {
            while (isActive) {
                delay(60 * 1000)

                val threshold = System.currentTimeMillis() - (15 * 60 * 1000)
                database.discoveredPeerDao().deleteStalePeers(threshold)
                Log.d(TAG, "🧹 Veritabanı Temizliği Yapıldı (15 dk kuralı)")
            }
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "❌ EmergencyService Destroyed")
        turnOffFlash()
        mediaPlayer?.release()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}