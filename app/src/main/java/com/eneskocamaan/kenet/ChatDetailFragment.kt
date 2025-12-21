package com.eneskocamaan.kenet

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Base64
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.eneskocamaan.kenet.data.api.ApiClient
import com.eneskocamaan.kenet.data.api.GatewaySmsRequest
import com.eneskocamaan.kenet.data.db.AppDatabase
import com.eneskocamaan.kenet.data.db.ContactEntity
import com.eneskocamaan.kenet.data.db.MessageEntity
import com.eneskocamaan.kenet.databinding.FragmentChatDetailBinding
import com.eneskocamaan.kenet.peer.MessageAdapter
import com.eneskocamaan.kenet.proto.GatewaySmsPacket
import com.eneskocamaan.kenet.proto.KenetPacket
import com.eneskocamaan.kenet.proto.MessagePacket
import com.eneskocamaan.kenet.security.CryptoManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class ChatDetailFragment : Fragment(R.layout.fragment_chat_detail) {

    private var _binding: FragmentChatDetailBinding? = null
    private val binding get() = _binding!!

    private val deviceAddress by lazy { arguments?.getString("deviceAddress") ?: "" }
    private val isSmsMode by lazy { arguments?.getBoolean("isSmsMode") ?: false }
    private val targetPhone by lazy { arguments?.getString("targetPhone") ?: "" }
    private val deviceName by lazy { arguments?.getString("deviceName") ?: "Bilinmeyen Cihaz" }

    private lateinit var messageAdapter: MessageAdapter
    private val db by lazy { AppDatabase.getDatabase(requireContext()) }
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private var myUserId: String? = null
    private var currentContact: ContactEntity? = null
    private var isDiscoveryInProgress = false

    // Gateway Modu için Sunucu ID'si (Sabit Public Key)
    private val SERVER_PUBLIC_KEY = "dKpEivuZY+rwyVdzxM8KHdi6TwuCWHWK0tycE0uGsEw="

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentChatDetailBinding.bind(view)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())

        CryptoManager.init(requireContext())

        setupToolbar()
        setupRecyclerView()
        initializeAndObserve()

        if (isSmsMode) binding.toolbar.subtitle = "SMS Ağ Geçidi Modu"

        binding.btnSend.setOnClickListener {
            if (binding.btnSend.isEnabled && binding.etMessageInput.text.isNotBlank()) {
                val msg = binding.etMessageInput.text.toString()
                DebugLogger.log("UI", "📤 Gönder Butonuna Basıldı. Mesaj: '$msg'")
                sendMessage(msg)
            } else {
                if (!isSmsMode) Toast.makeText(context, "Önce sağ üstten konumu keşfedin!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() { super.onResume(); markAsRead() }
    private fun markAsRead() { viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { db.messageDao().markMessagesAsRead(deviceAddress) } }

    private fun initializeAndObserve() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { myUserId = db.userDao().getMyUserId() }
        viewLifecycleOwner.lifecycleScope.launch {
            db.messageDao().getMessagesWith(deviceAddress).collect { messages ->
                withContext(Dispatchers.Main) {
                    messageAdapter.updateList(messages)
                    if (messages.isNotEmpty()) {
                        binding.chatRecyclerView.smoothScrollToPosition(messages.size - 1)
                        val lastMsg = messages.last()
                        if (!lastMsg.isSent && !lastMsg.isRead) markAsRead()
                    }
                }
            }
        }
        if (!isSmsMode) {
            viewLifecycleOwner.lifecycleScope.launch {
                db.contactDao().getContactByIdFlow(deviceAddress).collectLatest { contact ->
                    currentContact = contact
                    if (hasValidLocation(contact) && isDiscoveryInProgress) {
                        isDiscoveryInProgress = false
                        withContext(Dispatchers.Main) { Toast.makeText(context, "Konum bulundu!", Toast.LENGTH_SHORT).show() }
                    }
                    withContext(Dispatchers.Main) { updateUIState() }
                }
            }
        } else { updateUIState() }
    }

    private fun updateUIState() {
        if (isSmsMode) {
            binding.etMessageInput.isEnabled = true
            binding.etMessageInput.hint = "SMS mesajı yazın..."
            binding.btnSend.isEnabled = true; binding.btnSend.alpha = 1.0f
            return
        }
        val hasLoc = hasValidLocation(currentContact)
        if (isDiscoveryInProgress || !hasLoc) {
            binding.etMessageInput.isEnabled = false
            binding.etMessageInput.hint = if (isDiscoveryInProgress) "Konum aranıyor..." else "Mesaj atmak için konumu keşfedin ↗"
            binding.btnSend.isEnabled = false; binding.btnSend.alpha = 0.5f
        } else {
            binding.etMessageInput.isEnabled = true
            binding.etMessageInput.hint = "Bir mesaj yazın..."
            binding.btnSend.isEnabled = true; binding.btnSend.alpha = 1.0f
        }
    }

    private fun hasValidLocation(contact: ContactEntity?): Boolean {
        if (contact == null) return false
        return (contact.contactLatitude != null && contact.contactLatitude != 0.0)
    }

    private fun setupToolbar() {
        binding.toolbar.title = deviceName
        (requireActivity() as? androidx.appcompat.app.AppCompatActivity)?.setSupportActionBar(binding.toolbar)
        (requireActivity() as? androidx.appcompat.app.AppCompatActivity)?.supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true); setDisplayShowHomeEnabled(true)
        }
        binding.toolbar.navigationIcon?.setTint(Color.WHITE)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menu.clear(); menuInflater.inflate(R.menu.chat_menu, menu)
                val discoverItem = menu.findItem(R.id.action_discover_location)
                if (isSmsMode) discoverItem.isVisible = false else discoverItem.actionView?.setOnClickListener { startDiscovery() }

                val logItem = menu.add(Menu.NONE, 999, Menu.NONE, "Rapor")
                logItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                logItem.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_info_outline)
                logItem.icon?.mutate()?.setColorFilter(ContextCompat.getColor(context!!, R.color.primary_color), PorterDuff.Mode.SRC_IN)
                logItem.setOnMenuItemClickListener { showLogPopup(); true }
            }
            override fun onMenuItemSelected(menuItem: MenuItem): Boolean = if (menuItem.itemId == R.id.action_discover_location) { startDiscovery(); true } else false
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun showLogPopup() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_debug_log, null)
        val tvLogContent = dialogView.findViewById<TextView>(R.id.tv_log_content)
        val scrollView = dialogView.findViewById<ScrollView>(R.id.scroll_view_logs)
        tvLogContent.text = DebugLogger.getLogText().ifEmpty { "> Henüz log yok...\n" }
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        val dialog = AlertDialog.Builder(requireContext()).setView(dialogView).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialogView.findViewById<View>(R.id.btn_clear_logs).setOnClickListener { DebugLogger.clear(); tvLogContent.text = "> Temizlendi.\n" }
        dialogView.findViewById<View>(R.id.btn_close_logs).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun startDiscovery() {
        val senderId = myUserId ?: return
        val targetId = currentContact?.contactServerId ?: deviceAddress

        DebugLogger.log("UI", "📡 Keşfet butonuna basıldı. Hedef: $targetId")

        if (!SocketManager.isConnected) { Toast.makeText(context, "Ağ bağlantısı yok.", Toast.LENGTH_LONG).show(); return }
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return

        isDiscoveryInProgress = true
        updateUIState()
        Toast.makeText(context, "Sinyal gönderiliyor...", Toast.LENGTH_SHORT).show()

        fusedLocationClient.lastLocation.addOnSuccessListener { myLocation: Location? ->
            val myLat = myLocation?.latitude ?: 0.0
            val myLng = myLocation?.longitude ?: 0.0
            DebugLogger.log("UI", "📍 Konumum alındı: $myLat, $myLng")

            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val uniquePacketId = UUID.randomUUID().toString()
                    val discoveryPacket = com.eneskocamaan.kenet.proto.DiscoveryPacket.newBuilder()
                        .setPacketUid(PacketUtils.uuidToBytes(uniquePacketId))
                        .setSenderId(senderId).setTargetId(targetId)
                        .setSenderLat(myLat.toFloat()).setSenderLng(myLng.toFloat())
                        .setTtl(64).setTimestamp(System.currentTimeMillis())
                        .build()

                    val mainPacket = KenetPacket.newBuilder()
                        .setType(KenetPacket.PacketType.DISCOVERY)
                        .setDiscovery(discoveryPacket)
                        .build()

                    DebugLogger.log("PROTO", "🚀 Discovery Paketi Gönderiliyor... UID: $uniquePacketId")
                    SocketManager.write(mainPacket.toByteArray())
                } catch (e: Exception) { isDiscoveryInProgress = false; withContext(Dispatchers.Main) { updateUIState() } }
            }
        }.addOnFailureListener { isDiscoveryInProgress = false; updateUIState() }
    }

    private fun isInternetAvailable(): Boolean {
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // --- KRİTİK GÜNCELLEME: HİBRİT MANTIĞI (ÖNCE NET, YOKSA MESH) ---
    private fun sendMessage(text: String) {
        val senderId = myUserId ?: return

        DebugLogger.log("UI", "🔘 Gönder butonu tıklandı. Mod: ${if(isSmsMode) "SMS" else "P2P"}")

        // 1. SMS MODU (İnternet Varsa Direkt, Yoksa Mesh'e Yay)
        if (isSmsMode) {
            if (isInternetAvailable()) {
                // A) İnternet Var -> Direkt API'ye Git
                DebugLogger.log("UI", "🌍 [SMS] İnternet VAR. API'ye gönderiliyor...")
                sendDirectSmsToApi(text, senderId)
            } else {
                // B) İnternet YOK -> Mesh Ağına Yay (Başkası göndersin)
                DebugLogger.log("UI", "🚫 [SMS] İnternet YOK. Paket Mesh ağına yayılıyor (Relay)...")
                sendSmsToMesh(text, senderId)
            }
            return
        }

        // 2. MESH (P2P) MODU (Normal Mesajlaşma)
        val targetId = currentContact?.contactServerId ?: deviceAddress
        if (!SocketManager.isConnected) {
            DebugLogger.log("UI", "❌ [MESH] Socket bağlı değil.")
            Toast.makeText(context, "Ağ bağlantısı yok.", Toast.LENGTH_SHORT).show()
            return
        }

        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return

        fusedLocationClient.lastLocation.addOnSuccessListener { myLocation: Location? ->
            val myLat = (myLocation?.latitude ?: 0.0).toFloat()
            val myLng = (myLocation?.longitude ?: 0.0).toFloat()

            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val targetLat = (currentContact?.contactLatitude ?: 0.0).toFloat()
                val targetLng = (currentContact?.contactLongitude ?: 0.0).toFloat()
                val uniquePacketId = UUID.randomUUID().toString()

                val receiverPubKey = currentContact?.ibePublicKey ?: ""
                if (receiverPubKey.isEmpty()) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Anahtar eksik!", Toast.LENGTH_SHORT).show() }
                    return@launch
                }

                val cipherBox = CryptoManager.encrypt(text, receiverPubKey) ?: return@launch

                val msgPacket = MessagePacket.newBuilder()
                    .setPacketUid(PacketUtils.uuidToBytes(uniquePacketId))
                    .setSenderId(senderId).setTargetId(targetId)
                    .setSenderLat(myLat).setSenderLng(myLng)
                    .setTargetLat(targetLat).setTargetLng(targetLng)
                    .setTtl(10).setTimestamp(System.currentTimeMillis())
                    .setEncryptedPayload(PacketUtils.byteArrayToByteString(cipherBox.encryptedPayload))
                    .setNonce(PacketUtils.byteArrayToByteString(cipherBox.nonce))
                    .setEphemeralPublicKey(PacketUtils.byteArrayToByteString(cipherBox.ephemeralPublicKey))
                    .setIntegrityTag(PacketUtils.byteArrayToByteString(cipherBox.integrityTag))
                    .build()

                val mainPacket = KenetPacket.newBuilder()
                    .setType(KenetPacket.PacketType.MESSAGE)
                    .setMessage(msgPacket)
                    .build()

                DebugLogger.log("PROTO", "🚀 [MESH] Mesaj Paketi Gönderiliyor -> $targetId")
                SocketManager.write(mainPacket.toByteArray())

                saveMessageToDb(uniquePacketId, senderId, targetId, text, 1)
                withContext(Dispatchers.Main) { binding.etMessageInput.setText("") }
            }
        }
    }

    // --- YENİ FONKSİYON: SMS Paketini Mesh'e Yayma ---
    private fun sendSmsToMesh(text: String, senderId: String) {
        if (!SocketManager.isConnected) {
            Toast.makeText(context, "Ağ bağlantısı yok (Mesh kapalı).", Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val uniquePacketId = UUID.randomUUID().toString()

            // SERVER PUBLIC KEY ile şifrele (Sadece sunucu çözebilir)
            val cipherBox = CryptoManager.encrypt(text, SERVER_PUBLIC_KEY) ?: return@launch

            val gatewayPacket = GatewaySmsPacket.newBuilder()
                .setPacketUid(PacketUtils.uuidToBytes(uniquePacketId))
                .setSenderId(senderId)
                .setTargetPhone(targetPhone)
                .setTtl(20) // Hop limiti
                .setEncryptedPayload(PacketUtils.byteArrayToByteString(cipherBox.encryptedPayload))
                .setNonce(PacketUtils.byteArrayToByteString(cipherBox.nonce))
                .setEphemeralPublicKey(PacketUtils.byteArrayToByteString(cipherBox.ephemeralPublicKey))
                .setIntegrityTag(PacketUtils.byteArrayToByteString(cipherBox.integrityTag))
                .build()

            val mainPacket = KenetPacket.newBuilder()
                .setType(KenetPacket.PacketType.GATEWAY_SMS)
                .setGatewaySms(gatewayPacket)
                .build()

            DebugLogger.log("PROTO", "🚀 [MESH] Gateway SMS Paketi Yayılıyor... (İnternet arıyor)")
            SocketManager.write(mainPacket.toByteArray())

            saveMessageToDb(uniquePacketId, senderId, "GATEWAY", text, 1) // 1=Beklemede
            withContext(Dispatchers.Main) { binding.etMessageInput.setText("") }
        }
    }

    private fun sendDirectSmsToApi(text: String, senderId: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val userProfile = db.userDao().getUserProfile()
            val myPhone = userProfile?.phoneNumber ?: ""
            val uniquePacketId = UUID.randomUUID().toString()

            try {
                // Sunucuya Şifreli Gönderim
                val cipherBox = CryptoManager.encrypt(text, SERVER_PUBLIC_KEY)!!

                val request = GatewaySmsRequest(
                    packetUid = uniquePacketId,
                    senderId = senderId,
                    senderPhone = myPhone,
                    targetPhone = targetPhone,
                    encryptedPayload = Base64.encodeToString(cipherBox.encryptedPayload, Base64.NO_WRAP),
                    nonce = Base64.encodeToString(cipherBox.nonce, Base64.NO_WRAP),
                    ephemeralKey = Base64.encodeToString(cipherBox.ephemeralPublicKey, Base64.NO_WRAP),
                    integrityTag = Base64.encodeToString(cipherBox.integrityTag, Base64.NO_WRAP)
                )

                val response = ApiClient.api.sendGatewaySms(request)

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        Toast.makeText(context, "SMS İletildi (Direkt)", Toast.LENGTH_SHORT).show()
                        DebugLogger.log("UI", "✅ Direkt SMS Başarılı!")
                        saveMessageToDb(uniquePacketId, senderId, "GATEWAY", text, 2)
                        binding.etMessageInput.setText("")
                    } else {
                        Toast.makeText(context, "Sunucu Hatası (${response.code()})", Toast.LENGTH_SHORT).show()
                        DebugLogger.log("UI", "❌ Sunucu Hatası: ${response.code()}")

                        // Hata durumunda Mesh'e dene (Opsiyonel, şimdilik sadece logla)
                        DebugLogger.log("UI", "⚠️ API hatası sonrası Mesh'e denenebilir.")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    DebugLogger.log("UI", "❌ API Hatası: ${e.message}")
                    // İNTERNET HATASI ALINIRSA MESH'E DÖN
                    sendSmsToMesh(text, senderId)
                }
            }
        }
    }

    private suspend fun saveMessageToDb(uid: String, sId: String, rId: String, txt: String, status: Int) {
        val messageEntity = MessageEntity(
            packetUid = uid, senderId = sId, receiverId = rId, chatPartnerId = if(isSmsMode) targetPhone else deviceAddress,
            content = txt, timestamp = System.currentTimeMillis(), isSent = true, isRead = true, status = status
        )
        db.messageDao().insertMessage(messageEntity)
    }

    private fun setupRecyclerView() {
        messageAdapter = MessageAdapter(mutableListOf())
        binding.chatRecyclerView.apply { layoutManager = LinearLayoutManager(context).apply { stackFromEnd = true }; adapter = messageAdapter }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}