package com.eneskocamaan.kenet.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.eneskocamaan.kenet.R
import com.eneskocamaan.kenet.data.api.ApiClient
import com.eneskocamaan.kenet.data.api.SeismicSignalRequest
import com.eneskocamaan.kenet.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.LinkedList
import kotlin.math.abs
import kotlin.math.sqrt

class SeismicService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var powerManager: PowerManager
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    // --- ALGORİTMA AYARLARI (MÜHENDİSLİK PARAMETRELERİ) ---

    // 1. STA/LTA (Erken Uyarı - P Dalgası Tespiti)
    private val staBuffer = LinkedList<Float>()
    private val ltaBuffer = LinkedList<Float>()
    private val STA_WINDOW = 10   // 0.5 saniye (Şok penceresi) 25 yapılacak
    private val LTA_WINDOW = 250 // 20 saniye (Gürültü öğrenme penceresi - Artırıldı) 1000 yapılacak
    private val TRIGGER_RATIO = 2.5f // Oran eşiği (Hassasiyet için biraz yükseltildi) 4 yapılacak

    // 2. PGA (Şiddet Ölçümü - S Dalgası Kontrolü)
    // Deprem diyebilmek için minimum ivme (0.02g ~ 0.2 m/s2)
    private val MIN_PGA_THRESHOLD = 0.05f // 0.15 yapılacak

    // Değişkenler
    private var currentStaSum = 0.0f
    private var currentLtaSum = 0.0f
    private val gravity = FloatArray(3)
    private var isCalibrated = false
    private var lastSignalTime: Long = 0
    private var stabilityCounter = 0

    // Ekran Durumu Takibi
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // Ekran kapandı -> Analize başla
                    registerSensor()
                    if (wakeLock?.isHeld == false) wakeLock?.acquire(10*60*60*1000L)
                    broadcastStatus("Kalibrasyon (Ekran Kapalı)...")
                }
                Intent.ACTION_SCREEN_ON -> {
                    // Ekran açıldı -> Durdur (False positive önleme)
                    unregisterSensor()
                    if (wakeLock?.isHeld == true) wakeLock?.release()
                    resetBuffers()
                    broadcastStatus("Beklemede (Ekran Açık)")
                }
            }
        }
    }

    companion object {
        const val ACTION_STATUS_UPDATE = "com.eneskocamaan.kenet.ACTION_STATUS_UPDATE"
        const val EXTRA_STATUS = "extra_status"
        const val CHANNEL_ID = "kenet_seismic_channel"
        const val NOTIFICATION_ID = 999
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startMyForeground()

        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // WakeLock Ayarı
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Kenet:EarthquakeWakeLock")

        // Ekran Açık/Kapalı dinleyicisini kaydet
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)

        // Servis Durumu Hafızası
        getSharedPreferences("seismic_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("is_service_running", true).apply()

        // Başlangıç Durumu Kontrolü
        if (powerManager.isInteractive) {
            broadcastStatus("Beklemede (Ekran Açık)")
        } else {
            registerSensor()
            wakeLock?.acquire(10*60*60*1000L)
            broadcastStatus("Kalibrasyon...")
        }
    }

    private fun registerSensor() {
        if (accelerometer != null) {
            // SENSOR_DELAY_GAME: ~50Hz (Deprem dalgalarını yakalamak için ideal)
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun unregisterSensor() {
        sensorManager.unregisterListener(this)
    }

    private fun resetBuffers() {
        staBuffer.clear()
        ltaBuffer.clear()
        currentStaSum = 0f
        currentLtaSum = 0f
        stabilityCounter = 0
        isCalibrated = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Kullanıcı butona bastığında burası çalışır
        if (powerManager.isInteractive) {
            broadcastStatus("Beklemede (Ekranı Kapatın)")
        } else {
            resetBuffers()
            broadcastStatus("Kalibrasyon...")
        }
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            // --- 1. PGA (Peak Ground Acceleration) HESABI ---
            // Yerçekimi filtresi (High-Pass Filter)
            val alpha = 0.8f
            gravity[0] = alpha * gravity[0] + (1 - alpha) * x
            gravity[1] = alpha * gravity[1] + (1 - alpha) * y
            gravity[2] = alpha * gravity[2] + (1 - alpha) * z

            val lx = x - gravity[0]
            val ly = y - gravity[1]
            val lz = z - gravity[2]

            // Anlık İvme (m/s2 cinsinden)
            val currentAcc = sqrt((lx * lx + ly * ly + lz * lz).toDouble()).toFloat()

            // Gürültü Filtresi (0.05 m/s2 altındaki titreşimleri yoksay)
            if (currentAcc < 0.05f) {
                // Çok sessiz, veriyi 0 kabul etme, küçük bir gürültü olarak ekle (LTA bozulmasın)
                processStalta(0.001f, currentAcc)
            } else {
                processStalta(currentAcc, currentAcc)
            }
        }
    }

    private fun processStalta(energy: Float, realPga: Float) {
        // STA/LTA Tamponlarını Doldur
        staBuffer.add(energy)
        currentStaSum += energy
        if (staBuffer.size > STA_WINDOW) currentStaSum -= staBuffer.removeFirst()

        ltaBuffer.add(energy)
        currentLtaSum += energy
        if (ltaBuffer.size > LTA_WINDOW) currentLtaSum -= ltaBuffer.removeFirst()

        // Isınma Süresi (LTA dolana kadar bekle - Yaklaşık 10-20 sn)
        if (ltaBuffer.size < LTA_WINDOW) {
            return
        }

        if (!isCalibrated) {
            isCalibrated = true
            broadcastStatus("İzleniyor (Güvenli Mod)")
        }

        // --- MATEMATİKSEL ANALİZ ---
        val staAvg = currentStaSum / staBuffer.size
        val ltaAvg = currentLtaSum / ltaBuffer.size

        // Sıfıra bölünme hatası önlemi
        val safeLta = if (ltaAvg < 0.01f) 0.01f else ltaAvg

        // 1. Kriter: ORAN (Ani enerji patlaması mı?) -> P Dalgası
        val ratio = staAvg / safeLta

        // 2. Kriter: PGA (Yeterince güçlü mü?) -> S Dalgası / Şiddet
        // Sadece oran yetmez, masaya tırnakla vurunca da oran artar.
        // Gerçek bir yer hareketi için PGA'nın belirli bir seviyeyi (0.15 m/s2) geçmesi gerekir.

        if (ratio > TRIGGER_RATIO && realPga > MIN_PGA_THRESHOLD) {

            val currentTime = System.currentTimeMillis()
            // 15 saniyede bir sinyal at (Cooldown)
            if (currentTime - lastSignalTime > 15000) {
                lastSignalTime = currentTime

                // DEPREM TESPİT EDİLDİ!
                Log.w("SeismicCore", "TETİKLENDİ! Oran: $ratio | PGA: $realPga")
                broadcastStatus("⚠️ DEPREM SİNYALİ GÖNDERİLİYOR...")

                // Backend'e PGA değerini gönderiyoruz (Backend buna göre şiddet haritası çıkaracak)
                sendSignalToBackend(realPga.toDouble())
            }
        }
    }

    private fun sendSignalToBackend(pga: Double) {
        serviceScope.launch {
            try {
                val db = AppDatabase.getDatabase(applicationContext)
                val user = db.userDao().getUserProfile()

                if (user != null && user.latitude != null && user.longitude != null) {
                    val request = SeismicSignalRequest(user.userId, pga, user.latitude, user.longitude)
                    val response = ApiClient.api.sendSeismicSignal(request)
                    if (response.isSuccessful) {
                        Log.i("SeismicCore", "Sinyal başarıyla iletildi.")
                        // Kullanıcıya bilgi ver (Kısa süreliğine)
                        broadcastStatus("Sinyal İletildi - Analiz Devam Ediyor")
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun broadcastStatus(status: String) {
        val intent = Intent("com.eneskocamaan.kenet.ACTION_STATUS_UPDATE")
        intent.putExtra("extra_status", status)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun startMyForeground() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kenet Deprem Kalkanı")
            .setContentText("Cihaz ekranı kapandığında izleme başlar.")
            .setSmallIcon(R.drawable.ic_signal)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            try { startForeground(NOTIFICATION_ID, notification) } catch (e2: Exception){}
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Deprem İzleme", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        getSharedPreferences("seismic_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("is_service_running", false).apply()

        try { unregisterReceiver(screenReceiver) } catch (e: Exception) {}
        try { sensorManager.unregisterListener(this) } catch (e: Exception) {}
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}