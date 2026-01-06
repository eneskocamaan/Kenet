package com.eneskocamaan.kenet.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.util.Log
import com.eneskocamaan.kenet.data.db.AppDatabase
import com.eneskocamaan.kenet.data.db.DiscoveredPeerEntity
import com.eneskocamaan.kenet.proto.EmergencyBeacon
import com.eneskocamaan.kenet.service.MovementSensorService
import com.eneskocamaan.kenet.utils.EmergencyDataMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@SuppressLint("MissingPermission")
class WifiDirectEmergencyManager(private val context: Context, private val db: AppDatabase) {
    // Logları filtrelemek için bu etiketi kullan: KENET_SOS_WIFI
    private val TAG = "KENET_SOS_WIFI"

    private val manager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager?
    private val channel: WifiP2pManager.Channel? = manager?.initialize(context, context.mainLooper, null)
    private val scope = CoroutineScope(Dispatchers.IO)
    private var serviceRequest: WifiP2pDnsSdServiceRequest? = null

    // --- 1. GÖNDERME KISMI (BROADCAST) ---
    fun startBroadcasting() {
        // Hata kontrolü: Eğer Wi-Fi P2P desteklenmiyorsa log bas
        if (manager == null) {
            Log.e(TAG, "❌ HATA: Wi-Fi Manager NULL! Cihaz desteklemiyor olabilir.")
            return
        }
        if (channel == null) {
            Log.e(TAG, "❌ HATA: Wi-Fi Channel NULL! Kanal oluşturulamadı.")
            return
        }

        scope.launch {
            val userProfile = db.userDao().getUserProfile() ?: run {
                Log.e(TAG, "❌ Profil Bulunamadı! Wi-Fi paketi oluşturulamıyor.")
                return@launch
            }
            val moveScore = MovementSensorService.currentMovementScore

            val beacon = EmergencyBeacon.newBuilder()
                .setUserId(userProfile.userId)
                .setDisplayName(userProfile.displayName)
                .setBloodType(EmergencyDataMapper.stringToProtoBloodType(userProfile.bloodType))
                .setStatus(EmergencyBeacon.EmStatus.CRITICAL)
                .setLatitude(userProfile.latitude?.toFloat() ?: 0f)
                .setLongitude(userProfile.longitude?.toFloat() ?: 0f)
                .setMovementScore(moveScore)
                .setTimestamp(System.currentTimeMillis())
                .build()

            // Proto verisini Base64 string'e çevirip TXT kaydına gömüyoruz
            val base64Data = EmergencyDataMapper.protoToBase64(beacon)
            val record = mapOf("d" to base64Data, "t" to "SOS")

            val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(userProfile.userId, "_kenet._tcp", record)

            // Önce eski servisleri temizle, sonra yenisini ekle
            manager.clearLocalServices(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    manager.addLocalService(channel, serviceInfo, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            Log.i(TAG, "✅ Wi-Fi Yayını (DNS-SD) BAŞLADI! İsim: ${userProfile.displayName}")
                        }
                        override fun onFailure(reason: Int) {
                            Log.e(TAG, "❌ Wi-Fi Yayın Hatası Kod: $reason")
                        }
                    })
                }
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "❌ Eski servis temizleme hatası: $reason")
                }
            })
        }
    }

    fun stopBroadcasting() {
        manager?.clearLocalServices(channel, null)
        Log.d(TAG, "⏹ Wi-Fi Yayını Durduruldu.")
    }

    // --- 2. ALMA KISMI (DISCOVERY) ---
    fun startDiscovery() {
        if (manager == null || channel == null) {
            Log.e(TAG, "❌ HATA: Discovery başlatılamadı (Manager/Channel null).")
            return
        }

        // Listener tanımla: Sinyal gelince ne olacak?
        manager.setDnsSdResponseListeners(channel,
            { _, _, _ -> }, // Servis yanıtı (Boş geçiyoruz)
            { _, txtRecordMap, _ -> processTxtRecord(txtRecordMap) } // TXT Kaydı (Veri burada!)
        )

        serviceRequest = WifiP2pDnsSdServiceRequest.newInstance()

        manager.removeServiceRequest(channel, serviceRequest, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                manager.addServiceRequest(channel, serviceRequest, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        manager.discoverServices(channel, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() { Log.i(TAG, "🔍 Wi-Fi Taraması (Discovery) ÇALIŞIYOR...") }
                            override fun onFailure(reason: Int) { Log.e(TAG, "❌ Tarama Başlatılamadı Kod: $reason") }
                        })
                    }
                    override fun onFailure(reason: Int) { Log.e(TAG, "❌ Servis İsteği Eklenemedi: $reason") }
                })
            }
            override fun onFailure(reason: Int) { Log.e(TAG, "❌ Servis İsteği Silinemedi: $reason") }
        })
    }

    fun stopDiscovery() {
        manager?.clearServiceRequests(channel, null)
        Log.d(TAG, "⏹ Wi-Fi Taraması Durduruldu.")
    }

    // --- 3. VERİYİ İŞLEME VE DB'YE YAZMA ---
    private fun processTxtRecord(record: Map<String, String>) {
        if (record["t"] != "SOS") return
        val base64Data = record["d"] ?: return
        scope.launch {
            try {
                val beacon = EmergencyDataMapper.base64ToProto(base64Data) ?: return@launch
                val currentTime = System.currentTimeMillis()

                // SPAM KONTROLÜ
                val existingPeer = db.discoveredPeerDao().getPeerById(beacon.userId)
                if (existingPeer != null) {
                    val timeDiff = currentTime - existingPeer.lastSeenTimestamp
                    if (timeDiff < 5000 && Math.abs(existingPeer.movementScore - beacon.movementScore) < 10) {
                        return@launch
                    }
                }

                // HAREKET ANALİZİ
                var finalStatus = EmergencyDataMapper.mapProtoStatusToText(beacon.status)
                if (beacon.status != EmergencyBeacon.EmStatus.SAFE) {
                    finalStatus = if (beacon.movementScore < 20) "Kritik (Hareketsiz)" else "Acil (Hareketli)"
                }

                val entity = DiscoveredPeerEntity(
                    userId = beacon.userId,
                    displayName = beacon.displayName,
                    status = finalStatus,
                    bloodType = EmergencyDataMapper.protoBloodTypeToString(beacon.bloodType),
                    latitude = beacon.latitude.toDouble(),
                    longitude = beacon.longitude.toDouble(),
                    movementScore = beacon.movementScore,
                    distanceMeters = 0f,
                    lastSeenTimestamp = currentTime,
                    source = "WIFI"
                )

                db.discoveredPeerDao().insertOrUpdatePeer(entity)
                Log.i(TAG, "💾 Wi-Fi DB Güncellendi: ${entity.displayName}")

            } catch (e: Exception) { Log.e(TAG, "Parse Error: ${e.message}") }
        }
    }
}