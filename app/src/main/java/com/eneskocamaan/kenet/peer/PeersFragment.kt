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
        // --- ESKİ CİHAZ ENGELİ ---
        // Android 9.0 (API 28) ve altı engellensin
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            AlertDialog.Builder(requireContext())
                .setTitle("Cihaz Desteklenmiyor")
                .setMessage("Bu özellik yüksek performanslı ağ işlemleri gerektirir.\n\nCihazınızın Android sürümü (Android 9.0 altı) bu özelliği kararlı bir şekilde çalıştıramaz.")
                .setPositiveButton("Anladım", null)
                .show()
            return
        }

        // Destekliyorsa devam et
        val targetId = contact.contactServerId ?: contact.contactPhoneNumber
        if (targetId.isNullOrEmpty()) return

        val bundle = Bundle().apply {
            putString("deviceAddress", targetId)
            putString("deviceName", contact.contactName)
        }
        findNavController().navigate(R.id.action_peersFragment_to_chatDetailFragment, bundle)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}