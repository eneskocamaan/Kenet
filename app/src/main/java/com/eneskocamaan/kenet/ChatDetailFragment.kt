package com.eneskocamaan.kenet

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.eneskocamaan.kenet.data.db.AppDatabase
import com.eneskocamaan.kenet.data.db.MessageEntity
import com.eneskocamaan.kenet.databinding.FragmentChatDetailBinding
import com.eneskocamaan.kenet.peer.MessageAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Date

class ChatDetailFragment : Fragment(R.layout.fragment_chat_detail) {

    private var _binding: FragmentChatDetailBinding? = null
    private val binding get() = _binding!!

    // Argümanları güvenli şekilde alıyoruz
    private val deviceAddress by lazy { arguments?.getString("deviceAddress") ?: "Bilinmeyen Adres" }
    private val deviceName by lazy { arguments?.getString("deviceName") ?: "Bilinmeyen Cihaz" }

    private lateinit var messageAdapter: MessageAdapter

    // Veritabanı erişimi
    private val db by lazy { AppDatabase.getDatabase(requireContext()) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentChatDetailBinding.bind(view)

        setupToolbar()
        setupRecyclerView()

        // 1. Veritabanını Dinle (Mesaj geldiğinde MainActivity kaydeder, burası gösterir)
        observeMessages()

        // 2. Mesaj Gönderme Butonu
        binding.btnSend.setOnClickListener {
            val messageText = binding.etMessageInput.text.toString()
            if (messageText.isNotBlank()) {
                sendMessage(messageText)
            }
        }

        // NOT: SocketManager.onMessageReceived BURADAN KALDIRILDI.
        // Artık mesajları MainActivity dinliyor ve DB'ye kaydediyor.
        // Bu Fragment sadece DB'deki değişimi izliyor.
    }

    // --- VERİTABANI İZLEME VE OKUNDU İŞARETLEME ---
    private fun observeMessages() {
        lifecycleScope.launch {
            // DB'den bu kişiyle olan mesajları sürekli akış olarak al
            db.messageDao().getMessagesWith(deviceAddress).collect { messages ->

                // Listeyi güncelle
                messageAdapter.updateList(messages)

                if (messages.isNotEmpty()) {
                    // En son mesaja kaydır
                    binding.chatRecyclerView.smoothScrollToPosition(messages.size - 1)

                    // --- YENİ: MESAJLARI OKUNDU YAP ---
                    // Eğer okunmamış mesaj varsa (ve bana gelmişse), veritabanında "Okundu" olarak güncelle
                    val hasUnread = messages.any { !it.isRead && !it.isSent }
                    if (hasUnread) {
                        launch(Dispatchers.IO) {
                            db.messageDao().markMessagesAsRead(deviceAddress)
                        }
                    }
                }
            }
        }
    }

    // --- MESAJ GÖNDERME ---
    private fun sendMessage(text: String) {
        val myId = requireContext().myDeviceId

        // 1. Önce Socket ile karşıya gönder (Ağ İşlemi)
        SocketManager.write(text)

        // 2. Sonra Veritabanına Kaydet (Kalıcılık)
        val message = MessageEntity(
            senderId = myId,
            receiverId = deviceAddress,
            chatPartnerId = deviceAddress,
            content = text,
            timestamp = Date().time,
            isSent = true, // Ben gönderdim
            isRead = true  // Kendi mesajım zaten okunmuştur
        )

        lifecycleScope.launch(Dispatchers.IO) {
            db.messageDao().insertMessage(message)
        }

        binding.etMessageInput.setText("")
    }

    private fun setupToolbar() {
        binding.toolbar.title = deviceName
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupRecyclerView() {
        // MessageAdapter sınıfınızın doğru import edildiğinden emin olun
        messageAdapter = MessageAdapter(mutableListOf(), requireContext().myDeviceId)

        binding.chatRecyclerView.apply {
            layoutManager = LinearLayoutManager(context).apply {
                stackFromEnd = true
            }
            adapter = messageAdapter
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Listener temizliğine gerek kalmadı, çünkü MainActivity yönetiyor.
        _binding = null
    }
}