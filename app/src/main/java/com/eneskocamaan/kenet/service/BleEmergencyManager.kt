package com.eneskocamaan.kenet.network

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import com.eneskocamaan.kenet.data.db.AppDatabase
import com.eneskocamaan.kenet.data.db.DiscoveredPeerEntity
import com.eneskocamaan.kenet.proto.EmergencyBeacon
import com.eneskocamaan.kenet.service.MovementSensorService
import com.eneskocamaan.kenet.utils.EmergencyDataMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

@SuppressLint("MissingPermission")
class BleEmergencyManager(private val context: Context, private val db: AppDatabase) {
    private val TAG = "KENET_SOS_BLE"

    companion object {
        // Bu UUID, 1. Pakette (Advertising) gidecek
        val KENET_SERVICE_UUID: ParcelUuid = ParcelUuid(UUID.fromString("00001111-0000-1000-8000-00805F9B34FB"))

        // Bu ID, 2. Pakette (Scan Response) veriyi taşıyacak
        private const val MANUFACTURER_ID = 0xFFFF
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    private val advertiser: BluetoothLeAdvertiser? = adapter?.bluetoothLeAdvertiser
    private val scanner: BluetoothLeScanner? = adapter?.bluetoothLeScanner
    private val scope = CoroutineScope(Dispatchers.IO)

    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null

    // --- İZİN KONTROLÜ ---
    private fun hasBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "❌ HATA: Android 12+ için Bluetooth İzinleri EKSİK!")
                return false
            }
        }
        return true
    }

    // --- 1. GÖNDERME KISMI (2 Aşamalı) ---
    fun startAdvertising() {
        if (!hasBluetoothPermissions()) return
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "❌ BLE Kapalı! Yayın Başlatılamadı.")
            return
        }

        scope.launch {
            val userProfile = db.userDao().getUserProfile() ?: return@launch

            // --- ADIM 1: Veriyi Hazırla (Mini Paket) ---
            // Scan Response kapasitesi 31 byte. Bunun 2 byte'ı başlık. Kalır 29 byte.
            // Bu yüzden ismi kısaltmamız gerekebilir.

            var safeName = userProfile.displayName
            if (safeName.length > 8) {
                safeName = safeName.substring(0, 8) // İsmi 8 karaktere düşür sığması için
            }

            val miniBeacon = EmergencyBeacon.newBuilder()
                .setUserId(userProfile.userId.takeLast(4)) // ID'nin sadece son 4 hanesi (yer tasarrufu)
                .setDisplayName(safeName)
                .setBloodType(EmergencyDataMapper.stringToProtoBloodType(userProfile.bloodType))
                .setStatus(EmergencyBeacon.EmStatus.CRITICAL)
                .setLatitude(userProfile.latitude?.toFloat() ?: 0f)
                .setLongitude(userProfile.longitude?.toFloat() ?: 0f)
                .build()

            val beaconBytes = miniBeacon.toByteArray()
            Log.d(TAG, "📦 BLE Veri Paketi: ${beaconBytes.size} byte (Hedef < 29 byte)")

            // Güvenlik: Eğer hala büyükse uyar (ama göndermeyi dene)
            if (beaconBytes.size > 29) {
                Log.e(TAG, "⚠️ UYARI: Paket çok büyük (${beaconBytes.size} byte)! Kırpılması lazım.")
            }

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false) // Sadece yayın (Broadcast)
                .build()

            // --- ADIM 2: Paketleri Böl ---

            // PAKET A (Reklam): "Ben Kenet'im" (Sadece UUID taşır)
            val advertisingData = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(KENET_SERVICE_UUID)
                .build()

            // PAKET B (Yanıt): "İşte Verilerim" (Veriyi taşır)
            val scanResponseData = AdvertiseData.Builder()
                .addManufacturerData(MANUFACTURER_ID, beaconBytes)
                .build()

            advertiseCallback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    Log.i(TAG, "📡 [YAYINDA] BLE Sinyali Başladı! (Split Mode)")
                }
                override fun onStartFailure(errorCode: Int) {
                    Log.e(TAG, "❌ BLE Yayın Hatası! Kodu: $errorCode")
                    if(errorCode == AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE) {
                        Log.e(TAG, "⚠️ Paket hala sığmıyor! Proto dosyasını küçültmemiz gerek.")
                    }
                }
            }

            // İki paketi de veriyoruz
            advertiser?.startAdvertising(settings, advertisingData, scanResponseData, advertiseCallback)
        }
    }

    fun stopAdvertising() {
        if (!hasBluetoothPermissions()) return
        advertiseCallback?.let {
            advertiser?.stopAdvertising(it)
            advertiseCallback = null
            Log.d(TAG, "🔕 BLE Yayını Durduruldu.")
        }
    }

    // --- 2. ALMA KISMI (SCAN) ---
    fun startScanning() {
        if (!hasBluetoothPermissions()) return
        if (adapter == null || !adapter.isEnabled) return

        Log.i(TAG, "👀 BLE Taraması Başlatıldı...")

        val filter = ScanFilter.Builder().setServiceUuid(KENET_SERVICE_UUID).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.let { processScanResult(it) }
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { processScanResult(it) }
            }
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "❌ Tarama Hatası: $errorCode")
            }
        }

        scanner?.startScan(listOf(filter), settings, scanCallback)
    }

    fun stopScanning() {
        if (!hasBluetoothPermissions()) return
        scanCallback?.let {
            scanner?.stopScan(it)
            scanCallback = null
            Log.d(TAG, "😴 Taramayı Durduruldu.")
        }
    }

    // --- 3. BİRLEŞTİRME VE KAYIT ---
    private fun processScanResult(result: ScanResult) {
        scope.launch {
            try {
                // Veriyi al
                var data = result.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID)
                if (data == null) data = result.scanRecord?.getServiceData(KENET_SERVICE_UUID)
                if (data == null) return@launch

                val beacon = EmergencyBeacon.parseFrom(data)
                val distance = calculateDistance(result.rssi)
                val currentTime = System.currentTimeMillis()

                // SPAM KONTROLÜ: Önce veritabanındaki son kayda bak
                val existingPeer = db.discoveredPeerDao().getPeerById(beacon.userId)

                // Eğer kişi zaten varsa ve son güncelleme 5 saniye içindeyse VE durumu değişmediyse kaydetme
                if (existingPeer != null) {
                    val timeDiff = currentTime - existingPeer.lastSeenTimestamp
                    // Hareket skoru çok değişmediyse ve süre 5 saniyeden azsa pas geç
                    if (timeDiff < 5000 &&
                        Math.abs(existingPeer.movementScore - beacon.movementScore) < 10 &&
                        existingPeer.status == EmergencyDataMapper.mapProtoStatusToText(beacon.status)) {
                        return@launch
                    }
                }

                Log.i(TAG, "⚡ GÜNCEL VERİ: ${beacon.displayName} (Hareket: ${beacon.movementScore})")

                // HAREKET ANALİZİ: Skor 20'nin altındaysa KRİTİK, üstündeyse ACİL
                // Proto'dan gelen statüyü sensör verisiyle harmanlıyoruz
                var finalStatus = EmergencyDataMapper.mapProtoStatusToText(beacon.status)

                // Eğer gönderen "Güvende" demediyse, sensöre göre karar verelim
                if (beacon.status != EmergencyBeacon.EmStatus.SAFE) {
                    finalStatus = if (beacon.movementScore < 20) "Kritik (Hareketsiz)" else "Acil (Hareketli)"
                }

                val entity = DiscoveredPeerEntity(
                    userId = beacon.userId,
                    displayName = beacon.displayName,
                    status = finalStatus, // Güncellenmiş durum
                    bloodType = EmergencyDataMapper.protoBloodTypeToString(beacon.bloodType),
                    latitude = beacon.latitude.toDouble(),
                    longitude = beacon.longitude.toDouble(),
                    movementScore = beacon.movementScore,
                    distanceMeters = distance,
                    lastSeenTimestamp = currentTime,
                    source = "BLE"
                )

                db.discoveredPeerDao().insertOrUpdatePeer(entity)
                Log.d(TAG, "💾 DB Güncellendi: ${entity.displayName} | Skor: ${entity.movementScore}")

            } catch (e: Exception) {
                // Log.e(TAG, "Paket Hatası: ${e.message}")
            }
        }
    }

    private fun calculateDistance(rssi: Int): Float {
        return Math.pow(10.0, ((-59 - rssi) / 20.0)).toFloat()
    }
}