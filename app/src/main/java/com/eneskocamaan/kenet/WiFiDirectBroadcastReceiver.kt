package com.eneskocamaan.kenet

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import com.eneskocamaan.kenet.peer.PeersFragment

class WiFiDirectBroadcastReceiver(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val peersFragment: PeersFragment // Olayları işleyecek fragment
) : BroadcastReceiver() {

    @SuppressLint("MissingPermission") // İzinler PeersFragment içinde kontrol edildiği için burada suppress edebiliriz
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // 1. Wi-Fi P2P Açık mı Kapalı mı?
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                val statusText = if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    "Wi-Fi Direct Açık"
                } else {
                    "Wi-Fi Direct Kapalı - Lütfen Ayarlardan Açın"
                }
                peersFragment.updateStatus(statusText)
            }

            // 2. Cihaz Listesi Değişti (Yeni cihaz bulundu veya kayboldu)
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                // İzin kontrolü (Eski cihazlar ve yeni cihazlar için güvenli kontrol)
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    manager.requestPeers(channel, peersFragment)
                }
            }

            // 3. KRİTİK BÖLÜM: Bağlantı Durumu Değişti
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)

                if (networkInfo?.isConnected == true) {
                    // Bağlantı kuruldu! Detayları (IP adresi, kim Host vs.) iste.
                    // Bu çağrı, PeersFragment içindeki onConnectionInfoAvailable metodunu tetikler.
                    manager.requestConnectionInfo(channel, peersFragment)
                } else {
                    // Bağlantı koptu veya henüz kurulmadı
                    peersFragment.updateStatus("Bağlantı Kesildi veya Bekleniyor...")
                }
            }

            // 4. Bu cihazın durumu değişti (İsim değişimi vb.)
            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                // Şimdilik loglamak yeterli
                Log.d("KENET_WIFI", "Cihaz durumu güncellendi.")
            }
        }
    }
}