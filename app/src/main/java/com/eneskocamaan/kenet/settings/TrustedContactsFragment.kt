package com.eneskocamaan.kenet.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.eneskocamaan.kenet.R
import com.eneskocamaan.kenet.data.api.ApiClient
import com.eneskocamaan.kenet.data.api.ContactModel
import com.eneskocamaan.kenet.data.db.AppDatabase
import com.eneskocamaan.kenet.data.db.ContactEntity
import com.eneskocamaan.kenet.data.db.UserEntity
import com.eneskocamaan.kenet.data.model.remote.request.CompleteProfileRequest
import com.eneskocamaan.kenet.data.model.remote.request.DeleteContactRequest
import com.eneskocamaan.kenet.databinding.FragmentTrustedContactsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TrustedContactsFragment : Fragment(R.layout.fragment_trusted_contacts) {

    private var _binding: FragmentTrustedContactsBinding? = null
    private val binding get() = _binding!!

    private lateinit var contactsAdapter: SettingsContactsAdapter
    private var currentUser: UserEntity? = null

    // İzin Yöneticisi
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) navigateToContactSelection()
            else Toast.makeText(context, "Rehber izni gerekli.", Toast.LENGTH_SHORT).show()
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentTrustedContactsBinding.bind(view)

        setupRecyclerView()
        loadUserData()

        // Geri Butonu
        binding.btnBack.setOnClickListener {
            findNavController().popBackStack()
        }

        // Yeni Kişi Ekle
        binding.btnAddContact.setOnClickListener {
            checkPermissionAndAdd()
        }

        // Kişi Seçiminden Dönüşü Dinle
        setFragmentResultListener("requestKey_contacts") { _, bundle ->
            val selectedContacts = bundle.getParcelableArrayList<ContactModel>("selected_contacts")
            if (!selectedContacts.isNullOrEmpty()) {
                addNewContacts(selectedContacts)
            }
        }
    }

    private fun setupRecyclerView() {
        contactsAdapter = SettingsContactsAdapter { contactToDelete ->
            deleteContact(contactToDelete)
        }
        binding.rvContacts.layoutManager = LinearLayoutManager(requireContext())
        binding.rvContacts.adapter = contactsAdapter
    }

    private fun loadUserData() {
        lifecycleScope.launch {
            currentUser = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(requireContext()).userDao().getUserProfile()
            }
            currentUser?.let { user ->
                loadContacts(user.userId)
            }
        }
    }

    private fun loadContacts(userId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val contacts = AppDatabase.getDatabase(requireContext()).contactDao().getContacts(userId)
            withContext(Dispatchers.Main) {
                contactsAdapter.submitList(contacts)
                binding.tvEmptyState.visibility = if (contacts.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun checkPermissionAndAdd() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            navigateToContactSelection()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    private fun navigateToContactSelection() {
        findNavController().navigate(R.id.action_trustedContactsFragment_to_contactSelectionFragment)
    }

    // --- KİŞİ EKLEME ---
    private fun addNewContacts(newContacts: List<ContactModel>) {
        val phone = currentUser?.phoneNumber ?: return
        val userId = currentUser?.userId ?: return
        setLoading(true)

        lifecycleScope.launch {
            try {
                // Backend'e sadece profili (eski haliyle) ve yeni kontakları gönderiyoruz.
                // DÜZELTME: etDisplayName ve etBloodType bu ekranda yok. currentUser verilerini kullanıyoruz.
                // DÜZELTME: Named Arguments (phoneNumber = ...) kullanılarak hata giderildi.

                val request = CompleteProfileRequest(
                    phoneNumber = phone,
                    displayName = currentUser?.displayName ?: "",
                    bloodType = currentUser?.bloodType,
                    contacts = newContacts
                )
                val response = ApiClient.api.completeProfile(request)

                if (response.isSuccessful) {
                    // Local DB Insert
                    val entities = newContacts.map {
                        ContactEntity(userId, it.phone_number, it.display_name, null)
                    }
                    withContext(Dispatchers.IO) {
                        AppDatabase.getDatabase(requireContext()).contactDao().insertContacts(entities)
                    }
                    loadContacts(userId)
                    Toast.makeText(context, "Kişiler eklendi.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Ekleme sunucuda başarısız: ${response.code()}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Ekleme başarısız: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                setLoading(false)
            }
        }
    }

    // --- KİŞİ SİLME ---
    private fun deleteContact(contact: ContactEntity) {
        val ownerPhone = currentUser?.phoneNumber ?: return
        setLoading(true)

        lifecycleScope.launch {
            try {
                // DÜZELTME: DeleteContactRequest parametre isimlerini netleştirdik.
                val request = DeleteContactRequest(
                    ownerPhone = ownerPhone,
                    contactPhone = contact.contactPhoneNumber
                )
                val response = ApiClient.api.deleteContact(request)

                if (response.isSuccessful) {
                    withContext(Dispatchers.IO) {
                        AppDatabase.getDatabase(requireContext()).contactDao()
                            .deleteContact(currentUser!!.userId, contact.contactPhoneNumber)
                    }
                    loadContacts(currentUser!!.userId)
                    Toast.makeText(context, "Kişi silindi.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Silinemedi.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Bağlantı hatası.", Toast.LENGTH_SHORT).show()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun setLoading(isLoading: Boolean) {
        if (_binding != null) {
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.btnAddContact.isEnabled = !isLoading
            binding.rvContacts.alpha = if (isLoading) 0.5f else 1.0f
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}