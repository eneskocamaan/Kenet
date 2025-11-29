package com.eneskocamaan.kenet.peer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.eneskocamaan.kenet.GlobalVariables
import com.eneskocamaan.kenet.PeerAdapter
import com.eneskocamaan.kenet.R
import com.eneskocamaan.kenet.SocketManager
import com.eneskocamaan.kenet.WiFiDirectBroadcastReceiver
import com.eneskocamaan.kenet.data.db.AppDatabase
import com.eneskocamaan.kenet.databinding.FragmentPeersBinding
import com.eneskocamaan.kenet.myDeviceId
import kotlinx.coroutines.launch

class PeersFragment : Fragment(R.layout.fragment_peers), WifiP2pManager.PeerListListener, WifiP2pManager.ConnectionInfoListener {

    private var _binding: FragmentPeersBinding? = null
    private val binding get() = _binding!!

    private lateinit var manager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var receiver: WiFiDirectBroadcastReceiver
    private val intentFilter = IntentFilter()

    private val peers = mutableListOf<WifiP2pDevice>()
    private lateinit var adapter: PeerAdapter

    private var isConnectionInitiator = false
    private var currentConnectionInfo: WifiP2pInfo? = null

    // Okunmamış mesaj sayıları
    private val unreadMap = mutableMapOf<String, Int>()

    // Veritabanı (Lazy)
    private val db by lazy { AppDatabase.getDatabase(requireContext()) }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            startDiscovery()
        } else {
            showPermissionRationaleDialog()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentPeersBinding.bind(view)

        manager = requireActivity().getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(requireContext(), requireContext().mainLooper, null)

        intentFilter.apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }

        adapter = PeerAdapter(peers, unreadMap) { device ->
            connectToPeer(device)
        }

        binding.recyclerPeers.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@PeersFragment.adapter
        }

        binding.btnDiscoverPeers.setOnClickListener {
            checkAndRequestPermissions()
        }

        // --- YENİ EKLENEN: BİLDİRİM ROZETLERİNİ DİNLE ---
        observeUnreadBadges()
    }

    // --- YENİ FONKSİYON: DB'den Okunmamışları Takip Et ---
    private fun observeUnreadBadges() {
        lifecycleScope.launch {
            // DB'deki tüm okunmamış mesajları canlı izle
            db.messageDao().getAllUnreadMessages().collect { unreadMessages ->
                unreadMap.clear()

                // Mesajları gönderen kişiye (chatPartnerId) göre grupla ve say
                // Örn: { "A_Adresi": 3, "B_Adresi": 1 }
                val counts = unreadMessages.groupingBy { it.chatPartnerId }.eachCount()

                unreadMap.putAll(counts)

                // Listeyi güncelle ki kırmızı yuvarlaklar görünsün
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val requiredPermissions = getRequiredPermissions()
        val missingPermissions = requiredPermissions.filter {
            ActivityCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startDiscovery()
        } else {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        return permissions.toTypedArray()
    }

    private fun showPermissionRationaleDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("İzin Gerekli")
            .setMessage("Çevredeki cihazları bulabilmek için Konum ve Yakındaki Cihazlar izinlerine ihtiyacımız var.")
            .setPositiveButton("Ayarlara Git") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", requireContext().packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun startDiscovery() {
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                updateStatus("Tarama Başlatıldı. Cihaz aranıyor...")
            }
            override fun onFailure(reason: Int) {
                updateStatus("Tarama Başlatılamadı (Hata: $reason)")
            }
        })
    }

    private fun connectToPeer(device: WifiP2pDevice) {
        if (device.status == WifiP2pDevice.CONNECTED && currentConnectionInfo != null) {
            navigateToChat(currentConnectionInfo!!)
            return
        }

        updateStatus("Bağlanılıyor: ${device.deviceName}...")

        val config = WifiP2pConfig()
        config.deviceAddress = device.deviceAddress

        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        isConnectionInitiator = true

        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                updateStatus("İstek gönderildi: ${device.deviceName}")
            }
            override fun onFailure(reason: Int) {
                updateStatus("Bağlantı isteği başarısız: $reason")
                isConnectionInitiator = false
            }
        })
    }

    override fun onConnectionInfoAvailable(info: WifiP2pInfo?) {
        if (info == null || !info.groupFormed) return

        currentConnectionInfo = info
        val isHost = info.isGroupOwner
        val hostAddress = info.groupOwnerAddress?.hostAddress ?: ""

        // Global değişkene ata (MainActivity için)
        GlobalVariables.currentPeerAddress = if (isHost) "Client_Device" else hostAddress

        Log.d("KENET_CONN", "Bağlantı Kuruldu. Host: $hostAddress")

        // Soketi Başlat
        if (isHost) {
            SocketManager.startServer()
        } else {
            if (hostAddress.isNotEmpty()) {
                SocketManager.startClient(hostAddress)
            }
        }

        // Navigasyon
        if (isConnectionInitiator) {
            updateStatus("Bağlandı! Sohbet açılıyor...")
            navigateToChat(info)
            isConnectionInitiator = false
        } else {
            updateStatus("YENİ BAĞLANTI! Mesajlaşmak için cihaza tıklayın.")
            if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                manager.requestPeers(channel, this)
            }
        }
    }

    private fun navigateToChat(info: WifiP2pInfo) {
        val targetAddress = GlobalVariables.currentPeerAddress ?: "Unknown"
        // NOT: İleride burada MAC adresi eşleşmesi yapacağız.
        // Şimdilik test için "currentPeerAddress" kullanıyoruz.

        try {
            val bundle = Bundle().apply {
                putString("deviceAddress", targetAddress)
                putString("deviceName", "Bağlı Cihaz")
            }

            if (isAdded && findNavController().currentDestination?.id == R.id.peersFragment) {
                findNavController().navigate(R.id.action_peersFragment_to_chatDetailFragment, bundle)
            }
        } catch (e: Exception) {
            Log.e("KENET_NAV", "Navigasyon hatası: ${e.message}")
        }
    }

    fun updateStatus(message: String) {
        _binding?.tvPeerStatus?.text = message
    }

    override fun onPeersAvailable(peerList: WifiP2pDeviceList?) {
        if (!isAdded) return
        peers.clear()
        peerList?.deviceList?.let { peers.addAll(it) }
        adapter.notifyDataSetChanged()

        updateStatus("Bulunan: ${peers.size} cihaz. ID: ${requireContext().myDeviceId}")
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onResume() {
        super.onResume()
        receiver = WiFiDirectBroadcastReceiver(manager, channel, this)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_NOT_EXPORTED else 0
        requireActivity().registerReceiver(receiver, intentFilter, flags)
    }

    override fun onPause() {
        super.onPause()
        requireActivity().unregisterReceiver(receiver)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}