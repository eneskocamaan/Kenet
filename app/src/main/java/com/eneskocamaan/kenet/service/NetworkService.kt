package com.eneskocamaan.kenet.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import androidx.core.app.ActivityCompat
import com.eneskocamaan.kenet.DebugLogger
import com.eneskocamaan.kenet.PacketUtils // IMPORT ADDED
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
        DebugLogger.log("SERVICE", "🚀 NetworkService Started.")

        manager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager?.initialize(this, mainLooper, null)

        registerP2PReceiver()

        // Clean Start
        removeGroupAndStartDiscovery()
    }

    private fun removeGroupAndStartDiscovery() {
        if (!hasPermission()) return

        try {
            manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { startDiscovery() }
                override fun onFailure(reason: Int) { startDiscovery() }
            })
        } catch (e: SecurityException) {
            DebugLogger.log("ERROR", "RemoveGroup Permission Error: ${e.message}")
        }
    }

    private fun startDiscovery() {
        if (!hasPermission()) {
            DebugLogger.log("ERROR", "Discovery failed: No Permission!")
            return
        }

        try {
            manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    DebugLogger.log("P2P", "✅ Discovery Started.")
                }
                override fun onFailure(reason: Int) {
                    val errorMsg = getFailureReason(reason)
                    DebugLogger.log("P2P", "❌ Discovery Failed: $errorMsg. Retrying...")
                    // Retry after 3s
                    Handler(Looper.getMainLooper()).postDelayed({ startDiscovery() }, 3000)
                }
            })
        } catch (e: SecurityException) {
            DebugLogger.log("ERROR", "Discovery Security Error: ${e.message}")
        }
    }

    private fun sendHelloBeacon() {
        if (!SocketManager.isConnected) return

        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getDatabase(applicationContext)
            val myId = db.userDao().getMyUserId() ?: return@launch
            val myProfile = db.userDao().getUserProfile()

            // Discovery Packet kullanıyoruz (Çünkü Beacon açık bir yayındır)
            val uniquePacketId = UUID.randomUUID().toString()

            val beaconPayload = com.eneskocamaan.kenet.proto.DiscoveryPacket.newBuilder()
                .setPacketUid(PacketUtils.uuidToBytes(uniquePacketId))
                .setSenderId(myId)
                .setTargetId("BROADCAST") // Hedef Herkes
                .setSenderLat(myProfile?.latitude?.toFloat() ?: 0.0f)
                .setSenderLng(myProfile?.longitude?.toFloat() ?: 0.0f)
                .setTimestamp(System.currentTimeMillis())
                .setTtl(1) // Sadece 1 hop gitsin (komşular duysun)
                .build()

            val mainPacket = KenetPacket.newBuilder()
                .setType(KenetPacket.PacketType.DISCOVERY)
                .setDiscovery(beaconPayload)
                .build()

            SocketManager.write(mainPacket.toByteArray())
        }
    }

    override fun onPeersAvailable(peers: WifiP2pDeviceList?) {
        val deviceList = peers?.deviceList ?: emptyList()

        for (device in deviceList) {
            // 1. If Already Connected
            if (device.status == WifiP2pDevice.CONNECTED) {
                if (!SocketManager.isConnected) {
                    DebugLogger.log("P2P", "⚡ ${device.deviceName} already connected, establishing Socket...")
                    requestConnectionInfo()
                }
                break
            }

            // 2. If Available -> Connect
            if (device.status == WifiP2pDevice.AVAILABLE && !isConnecting && !SocketManager.isConnected) {
                connectToDevice(device)
                break
            }
        }
    }

    private fun connectToDevice(device: WifiP2pDevice) {
        if (!hasPermission()) return

        DebugLogger.log("P2P", "⚡ Auto Connect: ${device.deviceName}")
        isConnecting = true

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            // groupOwnerIntent = 0 // Uncomment for J7 or legacy devices
        }

        try {
            manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    DebugLogger.log("P2P", "✅ Connection Request Sent: ${device.deviceName}")
                }
                override fun onFailure(reason: Int) {
                    DebugLogger.log("P2P", "❌ Connection Request Failed: ${getFailureReason(reason)}")
                    isConnecting = false
                }
            })
        } catch (e: SecurityException) {
            DebugLogger.log("ERROR", "Connect Security Error: ${e.message}")
            isConnecting = false
        }
    }

    private fun requestConnectionInfo() {
        manager?.requestConnectionInfo(channel) { info ->
            if (info.groupFormed) {
                DebugLogger.log("P2P", "🔗 P2P Group Formed. GO: ${info.isGroupOwner}")
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
                        if (hasPermission()) {
                            manager?.requestPeers(channel, this@NetworkService)
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                        if (networkInfo?.isConnected == true) {
                            isConnecting = false
                            requestConnectionInfo()
                        } else {
                            if (SocketManager.isConnected) {
                                DebugLogger.log("P2P", "💔 Connection Lost. Restarting discovery...")
                            }
                            isConnecting = false
                            SocketManager.close()
                            beaconHandler.removeCallbacks(beaconRunnable)
                            Handler(Looper.getMainLooper()).postDelayed({ startDiscovery() }, 2000)
                        }
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, intentFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, intentFilter)
        }
    }

    private fun hasPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun getFailureReason(reason: Int): String {
        return when(reason) {
            WifiP2pManager.P2P_UNSUPPORTED -> "P2P Unsupported"
            WifiP2pManager.BUSY -> "System Busy"
            WifiP2pManager.ERROR -> "General Error"
            else -> "Unknown ($reason)"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        DebugLogger.log("SERVICE", "🛑 Service Stopping...")
        try {
            unregisterReceiver(receiver)
            beaconHandler.removeCallbacks(beaconRunnable)
            if (hasPermission()) {
                manager?.removeGroup(channel, null)
                manager?.stopPeerDiscovery(channel, null)
            }
            SocketManager.close()
        } catch (e: Exception) {
            DebugLogger.log("ERROR", "Destroy Error: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
    inner class LocalBinder : Binder() { fun getService(): NetworkService = this@NetworkService }
}