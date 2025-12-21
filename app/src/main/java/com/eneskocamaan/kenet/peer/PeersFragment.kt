package com.eneskocamaan.kenet.peer

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.eneskocamaan.kenet.R
import com.eneskocamaan.kenet.data.db.AppDatabase
import com.eneskocamaan.kenet.data.db.ContactEntity
import com.eneskocamaan.kenet.data.db.ContactWithUnreadCount
import com.eneskocamaan.kenet.databinding.FragmentPeersBinding
import kotlinx.coroutines.launch

class PeersFragment : Fragment(R.layout.fragment_peers) {

    private var _binding: FragmentPeersBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: PeerAdapter
    private val db by lazy { AppDatabase.getDatabase(requireContext()) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentPeersBinding.bind(view)

        adapter = PeerAdapter(emptyList()) { contact ->
            checkVersionAndNavigate(contact)
        }

        binding.recyclerPeers.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@PeersFragment.adapter
        }

        observeContacts()
    }

    private fun observeContacts() {
        lifecycleScope.launch {
            db.contactDao().getContactsWithUnreadCounts().collect { contacts ->
                val sorted = contacts.sortedWith(
                    compareByDescending<ContactWithUnreadCount> { it.unreadCount > 0 }
                        .thenByDescending { !it.contact.contactServerId.isNullOrEmpty() }
                )
                adapter.updateList(sorted)
            }
        }
    }

    private fun checkVersionAndNavigate(contact: ContactEntity) {
        // 1. Android Sürüm Kontrolü (Legacy)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            // ... (Popup Kodu Aynı) ...
            return
        }

        // 2. Kenet Kullanıcısı Mı? (GATEWAY KONTROLÜ)
        val isKenetUser = !contact.contactServerId.isNullOrEmpty()
        val targetPhone = contact.contactPhoneNumber

        if (!isKenetUser) {
            // Popup Göster
            AlertDialog.Builder(requireContext())
                .setTitle("SMS Ağ Geçidi Modu")
                .setMessage("Bu kişi KENET kullanmıyor.\n\nMesajınız, çevredeki internet bağlantısı olan en yakın cihaz (Gateway) üzerinden sunucuya iletilecek ve kişiye SMS olarak gönderilecektir.\n\nDevam etmek istiyor musunuz?")
                .setPositiveButton("Evet, Mesaj Gönder") { _, _ ->
                    // Sohbeti "SMS Modu"nda aç
                    val bundle = Bundle().apply {
                        putString("deviceAddress", targetPhone) // ID yerine Telefon No
                        putString("deviceName", contact.contactName)
                        putBoolean("isSmsMode", true) // YENİ BAYRAK
                        putString("targetPhone", targetPhone)
                    }
                    findNavController().navigate(R.id.action_peersFragment_to_chatDetailFragment, bundle)
                }
                .setNegativeButton("İptal", null)
                .show()
        } else {
            // Normal Sohbet (Doğrudan P2P)
            val targetId = contact.contactServerId ?: return
            val bundle = Bundle().apply {
                putString("deviceAddress", targetId)
                putString("deviceName", contact.contactName)
                putBoolean("isSmsMode", false)
            }
            findNavController().navigate(R.id.action_peersFragment_to_chatDetailFragment, bundle)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}