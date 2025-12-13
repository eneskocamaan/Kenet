package com.eneskocamaan.kenet.peer

import android.os.Bundle
import android.util.Log
import android.view.View
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
            navigateToChat(contact)
        }

        binding.recyclerPeers.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@PeersFragment.adapter
        }

        observeContacts()
    }

    private fun observeContacts() {
        lifecycleScope.launch {
            // YENİ METOT: Hem kişileri hem sayaçları dinle
            db.contactDao().getContactsWithUnreadCounts().collect { contactsWithCount ->

                // --- SIRALAMA MANTIĞI ---
                // 1. Okunmamış mesajı olanlar EN ÜSTTE
                // 2. Sonra Kenet kullanıcıları
                val sortedContacts = contactsWithCount.sortedWith(
                    compareByDescending<ContactWithUnreadCount> { it.unreadCount > 0 }
                        .thenByDescending { !it.contact.contactServerId.isNullOrEmpty() }
                )

                adapter.updateList(sortedContacts)
            }
        }
    }

    private fun navigateToChat(contact: ContactEntity) {
        val targetId = if (!contact.contactServerId.isNullOrEmpty()) {
            contact.contactServerId
        } else {
            contact.contactPhoneNumber
        }

        if (targetId.isNullOrEmpty()) {
            Log.e("KENET_NAV", "Hata: ID yok.")
            return
        }

        try {
            val bundle = Bundle().apply {
                putString("deviceAddress", targetId)
                putString("deviceName", contact.contactName)
                putBoolean("isRegistered", !contact.contactServerId.isNullOrEmpty())
            }

            if (isAdded && findNavController().currentDestination?.id == R.id.peersFragment) {
                findNavController().navigate(R.id.action_peersFragment_to_chatDetailFragment, bundle)
            }
        } catch (e: Exception) {
            Log.e("KENET_NAV", "Navigasyon hatası: ${e.message}")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}