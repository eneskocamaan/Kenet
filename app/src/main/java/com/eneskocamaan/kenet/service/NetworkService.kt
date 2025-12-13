package com.eneskocamaan.kenet.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.eneskocamaan.kenet.SocketManager
import com.eneskocamaan.kenet.data.db.AppDatabase
import com.eneskocamaan.kenet.proto.KenetPacket
import com.eneskocamaan.kenet.proto.MessagePacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Date
import java.util.UUID

class NetworkService : Service(), WifiP2pManager.PeerListListener {

    private val binder = LocalBinder()
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var isConnecting = false

    private val beaconHandler = Handler(Looper.getMainLooper())
    private val beaconRunnable = object : Runnable {
        override fun run() {
            sendHelloBeacon()
            beaconHandler.postDelayed(this, 5000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("KENET_SERVICE", "🚀 NetworkService Başlatıldı.")

        manager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager?.initialize(this, mainLooper, null)

        registerP2PReceiver()

        // Temiz Başlangıç
        manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { startDiscovery() }
            override fun onFailure(reason: Int) { startDiscovery() }
        })
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d("KENET_P2P", "✅ Keşif Başlatıldı.") }
            override fun onFailure(reason: Int) {
                // Hata alırsak 3sn sonra tekrar dene
                Handler(Looper.getMainLooper()).postDelayed({ startDiscovery() }, 3000)
            }
        })
    }

    private fun sendHelloBeacon() {
        if (!SocketManager.isConnected) return

        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getDatabase(applicationContext)
            val myId = db.userDao().getMyUserId() ?: return@launch
            val myProfile = db.userDao().getUserProfile()

            val beaconPayload = MessagePacket.newBuilder()
                .setPacketUid(UUID.randomUUID().toString())
                .setSenderId(myId)
                .setTargetId("BROADCAST")
                .setSenderLat(myProfile?.latitude ?: 0.0)
                .setSenderLng(myProfile?.longitude ?: 0.0)
                .setTimestamp(Date().time)
                .setContentText("B")
                .build()

            val mainPacket = KenetPacket.newBuilder()
                .setType(KenetPacket.PacketType.MESSAGE)
                .setMessage(beaconPayload)
                .build()

            SocketManager.write(mainPacket.toByteArray())
        }
    }

    override fun onPeersAvailable(peers: WifiP2pDeviceList?) {
        val deviceList = peers?.deviceList ?: emptyList()

        for (device in deviceList) {
            // 1. Zaten Bağlıysa
            if (device.status == WifiP2pDevice.CONNECTED) {
                if (!SocketManager.isConnected) {
                    manager?.requestConnectionInfo(channel) { info ->
                        if (info.groupFormed) {
                            if (info.isGroupOwner) {
                                SocketManager.startServer()
                                beaconHandler.post(beaconRunnable)
                            } else {
                                info.groupOwnerAddress?.hostAddress?.let {
                                    SocketManager.startClient(it)
                                    beaconHandler.post(beaconRunnable)
                                }
                            }
                        }
                    }
                }
                break
            }

            // 2. Bağlı Değilse -> Bağlan
            if (device.status == WifiP2pDevice.AVAILABLE && !isConnecting && !SocketManager.isConnected) {
                isConnecting = true
                val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }

                manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { Log.d("KENET_P2P", "Bağlantı İsteği Gönderildi: ${device.deviceName}") }
                    override fun onFailure(reason: Int) { isConnecting = false }
                })
                break
            }
        }
    }

    private fun registerP2PReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        manager?.requestPeers(channel, this@NetworkService)
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                        if (networkInfo?.isConnected == true) {
                            isConnecting = false
                            manager?.requestConnectionInfo(channel) { info ->
                                if (info.groupFormed) {
                                    if (info.isGroupOwner) {
                                        SocketManager.startServer()
                                        beaconHandler.post(beaconRunnable)
                                    } else {
                                        info.groupOwnerAddress?.hostAddress?.let {
                                            SocketManager.startClient(it)
                                            beaconHandler.post(beaconRunnable)
                                        }
                                    }
                                }
                            }
                        } else {
                            isConnecting = false
                            SocketManager.close()
                            beaconHandler.removeCallbacks(beaconRunnable)
                            Handler(Looper.getMainLooper()).postDelayed({ startDiscovery() }, 2000)
                        }
                    }
                }
            }
        }

        // Sadece Android 13+ için Flag kontrolü, geri kalanı standart
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, intentFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, intentFilter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
        beaconHandler.removeCallbacks(beaconRunnable)
        manager?.removeGroup(channel, null)
        manager?.stopPeerDiscovery(channel, null)
        SocketManager.close()
    }

    override fun onBind(intent: Intent?): IBinder = binder
    inner class LocalBinder : Binder() { fun getService(): NetworkService = this@NetworkService }
}