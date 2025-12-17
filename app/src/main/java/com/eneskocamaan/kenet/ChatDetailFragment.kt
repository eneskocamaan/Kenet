package com.eneskocamaan.kenet

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.location.Location
import android.os.Bundle
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
import com.eneskocamaan.kenet.data.db.AppDatabase
import com.eneskocamaan.kenet.data.db.ContactEntity
import com.eneskocamaan.kenet.data.db.MessageEntity
import com.eneskocamaan.kenet.databinding.FragmentChatDetailBinding
import com.eneskocamaan.kenet.peer.MessageAdapter
import com.eneskocamaan.kenet.proto.KenetPacket
import com.eneskocamaan.kenet.proto.MessagePacket
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
    private val deviceName by lazy { arguments?.getString("deviceName") ?: "Bilinmeyen Cihaz" }

    private lateinit var messageAdapter: MessageAdapter
    private val db by lazy { AppDatabase.getDatabase(requireContext()) }
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private var myUserId: String? = null
    private var currentContact: ContactEntity? = null
    private var isDiscoveryInProgress = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentChatDetailBinding.bind(view)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())

        setupToolbar()
        setupRecyclerView()
        initializeAndObserve()

        binding.btnSend.setOnClickListener {
            if (binding.btnSend.isEnabled) {
                val messageText = binding.etMessageInput.text.toString()
                if (messageText.isNotBlank()) {
                    sendMessage(messageText)
                }
            } else {
                Toast.makeText(context, "Önce sağ üstten konumu keşfedin!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Ekran ilk açıldığında okundu yap
        markAsRead()
    }

    private fun markAsRead() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            db.messageDao().markMessagesAsRead(deviceAddress)
        }
    }

    private fun initializeAndObserve() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            myUserId = db.userDao().getMyUserId()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            db.messageDao().getMessagesWith(deviceAddress).collect { messages ->
                withContext(Dispatchers.Main) {
                    messageAdapter.updateList(messages)
                    if (messages.isNotEmpty()) {
                        binding.chatRecyclerView.smoothScrollToPosition(messages.size - 1)

                        // --- DÜZELTME (MADDE 1): CANLI OKUNDU İŞARETLEME ---
                        // Eğer ekrandaysak ve son mesaj bana geldiyse ve okunmamışsa -> Hemen okundu yap.
                        val lastMsg = messages.last()
                        if (!lastMsg.isSent && !lastMsg.isRead) {
                            markAsRead()
                        }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            db.contactDao().getContactByIdFlow(deviceAddress).collectLatest { contact ->
                currentContact = contact
                if (hasValidLocation(contact) && isDiscoveryInProgress) {
                    isDiscoveryInProgress = false
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Konum bulundu! Mesajlaşabilirsiniz.", Toast.LENGTH_SHORT).show()
                    }
                }
                withContext(Dispatchers.Main) { updateUIState() }
            }
        }
    }

    private fun updateUIState() {
        val hasLoc = hasValidLocation(currentContact)
        if (isDiscoveryInProgress) {
            binding.etMessageInput.isEnabled = false
            binding.etMessageInput.hint = "Konum aranıyor..."
            binding.btnSend.isEnabled = false
            binding.btnSend.alpha = 0.5f
        } else if (!hasLoc) {
            binding.etMessageInput.isEnabled = false
            binding.etMessageInput.hint = "Mesaj atmak için konumu keşfedin ↗"
            binding.btnSend.isEnabled = false
            binding.btnSend.alpha = 0.5f
        } else {
            binding.etMessageInput.isEnabled = true
            binding.etMessageInput.hint = "Bir mesaj yazın..."
            binding.btnSend.isEnabled = true
            binding.btnSend.alpha = 1.0f
        }
    }

    private fun hasValidLocation(contact: ContactEntity?): Boolean {
        if (contact == null) return false
        return (contact.contactLatitude != null && contact.contactLatitude != 0.0 &&
                contact.contactLongitude != null && contact.contactLongitude != 0.0)
    }

    // --- SETUP TOOLBAR (LOG BUTONU EKLENDİ) ---
    private fun setupToolbar() {
        binding.toolbar.title = deviceName
        (requireActivity() as? androidx.appcompat.app.AppCompatActivity)?.setSupportActionBar(binding.toolbar)
        (requireActivity() as? androidx.appcompat.app.AppCompatActivity)?.supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
        binding.toolbar.navigationIcon?.setTint(Color.WHITE)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menu.clear()
                menuInflater.inflate(R.menu.chat_menu, menu)

                // 1. KEŞFET BUTONU
                val discoverItem = menu.findItem(R.id.action_discover_location)
                discoverItem?.actionView?.setOnClickListener { startDiscovery() }

                // 2. LOG BUTONU (Yeni İstek) - Dinamik olarak ekliyoruz
                val logItem = menu.add(Menu.NONE, 999, Menu.NONE, "Rapor")
                logItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                logItem.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_info_outline) // info ikonu lazım

                // İkonu Mavi Yap
                logItem.icon
                    ?.mutate()
                    ?.setColorFilter(
                        ContextCompat.getColor(context, R.color.primary_color),
                        PorterDuff.Mode.SRC_IN
                    )

                logItem.setOnMenuItemClickListener {
                    showLogPopup()
                    true
                }
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_discover_location -> {
                        startDiscovery()
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    // --- LOG POPUP GÖSTERİMİ ---
    // --- MODERN LOG POPUP GÖSTERİMİ ---
    private fun showLogPopup() {
        val context = requireContext()

        // 1. Tasarımı Bağla (Inflate)
        val dialogView = android.view.LayoutInflater.from(context).inflate(R.layout.dialog_debug_log, null)

        // 2. View Elemanlarını Bul
        val tvLogContent = dialogView.findViewById<TextView>(R.id.tv_log_content)
        val scrollView = dialogView.findViewById<ScrollView>(R.id.scroll_view_logs)
        val btnClear = dialogView.findViewById<TextView>(R.id.btn_clear_logs)
        val btnClose = dialogView.findViewById<android.widget.Button>(R.id.btn_close_logs)

        // 3. Logları Getir ve Yazdır
        val logs = DebugLogger.getLogText()
        tvLogContent.text = if (logs.isEmpty()) "> Henüz log yok...\n" else logs

        // 4. Scroll'u En Alta İndir (Otomatik)
        scrollView.post {
            scrollView.fullScroll(View.FOCUS_DOWN)
        }

        // 5. Dialog Oluştur (Arkaplanı şeffaf yap ki CardView kösesi yuvarlak görünsün)
        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .create()

        // Dialog arkaplanını şeffaf yap (Böylece XML'deki CardView tasarımı bozulmaz)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // 6. Buton İşlevleri
        btnClear.setOnClickListener {
            DebugLogger.clear()
            tvLogContent.text = "> Loglar temizlendi.\n"
            Toast.makeText(context, "Terminal temizlendi", Toast.LENGTH_SHORT).show()
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun startDiscovery() {
        val senderId = myUserId ?: return
        val targetId = currentContact?.contactServerId ?: deviceAddress

        // Android Sürüm Kontrolü (Legacy)
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) {
            Toast.makeText(context, "Android 9 altı cihazlarda bu özellik kısıtlıdır.", Toast.LENGTH_LONG).show()
            // Yine de devam etsin mi? Kısıtlı modda devam edebilir veya return
            // return
        }

        if (!SocketManager.isConnected) {
            Toast.makeText(context, "Ağ bağlantısı bekleniyor...", Toast.LENGTH_LONG).show()
            return
        }

        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(requireContext(), "Konum izni gerekli!", Toast.LENGTH_SHORT).show()
            return
        }

        isDiscoveryInProgress = true
        updateUIState()
        Toast.makeText(context, "Sinyal gönderiliyor...", Toast.LENGTH_SHORT).show()

        DebugLogger.log("UI", "Keşif butonu tıklandı. Hedef: $deviceName")

        fusedLocationClient.lastLocation.addOnSuccessListener { myLocation: Location? ->
            val myLat = myLocation?.latitude ?: 0.0
            val myLng = myLocation?.longitude ?: 0.0

            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val discoveryPacket = com.eneskocamaan.kenet.proto.DiscoveryPacket.newBuilder()
                        .setPacketUid(UUID.randomUUID().toString())
                        .setSenderId(senderId).setTargetId(targetId)
                        .setSenderLat(myLat).setSenderLng(myLng)
                        .setTtl(64).setTimestamp(System.currentTimeMillis())
                        .build()

                    val mainPacket = KenetPacket.newBuilder()
                        .setType(KenetPacket.PacketType.DISCOVERY)
                        .setDiscovery(discoveryPacket)
                        .build()

                    SocketManager.write(mainPacket.toByteArray())
                    DebugLogger.log("UI", "Keşif paketi sokete yazıldı.")

                } catch (e: Exception) {
                    DebugLogger.log("UI", "Keşif Hatası: ${e.message}")
                    isDiscoveryInProgress = false
                    withContext(Dispatchers.Main) { updateUIState() }
                }
            }
        }.addOnFailureListener {
            DebugLogger.log("UI", "Konum alınamadı: ${it.message}")
            isDiscoveryInProgress = false
            updateUIState()
            Toast.makeText(context, "Konum alınamadı.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendMessage(text: String) {
        val senderId = myUserId ?: return
        val targetId = currentContact?.contactServerId ?: deviceAddress

        if (!SocketManager.isConnected) {
            Toast.makeText(context, "Bağlantı koptu. Mesaj gönderilemedi.", Toast.LENGTH_SHORT).show()
            DebugLogger.log("UI", "Mesaj atılamadı: Bağlantı yok.")
            return
        }

        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return

        fusedLocationClient.lastLocation.addOnSuccessListener { myLocation: Location? ->
            val myLat = myLocation?.latitude ?: 0.0
            val myLng = myLocation?.longitude ?: 0.0

            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val targetLat = currentContact?.contactLatitude ?: 0.0
                val targetLng = currentContact?.contactLongitude ?: 0.0
                val uniquePacketId = UUID.randomUUID().toString()

                val msgPacket = MessagePacket.newBuilder()
                    .setPacketUid(uniquePacketId)
                    .setSenderId(senderId).setTargetId(targetId)
                    .setSenderLat(myLat).setSenderLng(myLng)
                    .setTargetLat(targetLat).setTargetLng(targetLng)
                    .setTtl(10).setTimestamp(System.currentTimeMillis())
                    .setContentText(text)
                    .build()

                val mainPacket = KenetPacket.newBuilder()
                    .setType(KenetPacket.PacketType.MESSAGE)
                    .setMessage(msgPacket)
                    .build()

                SocketManager.write(mainPacket.toByteArray())
                DebugLogger.log("UI", "Mesaj gönderildi: $text")

                val messageEntity = MessageEntity(
                    packetUid = uniquePacketId,
                    senderId = senderId, receiverId = targetId, chatPartnerId = deviceAddress,
                    content = text, timestamp = System.currentTimeMillis(),
                    isSent = true, isRead = true, status = 1
                )
                db.messageDao().insertMessage(messageEntity)
                withContext(Dispatchers.Main) { binding.etMessageInput.setText("") }
            }
        }
    }

    private fun setupRecyclerView() {
        messageAdapter = MessageAdapter(mutableListOf())
        binding.chatRecyclerView.apply {
            layoutManager = LinearLayoutManager(context).apply {
                stackFromEnd = true
            }
            adapter = messageAdapter
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}