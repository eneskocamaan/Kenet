package com.eneskocamaan.kenet.registration

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.eneskocamaan.kenet.R
import com.eneskocamaan.kenet.data.api.ApiClient
import com.eneskocamaan.kenet.data.api.CompleteProfileRequest
import com.eneskocamaan.kenet.data.api.ContactModel
import com.eneskocamaan.kenet.data.db.AppDatabase
import com.eneskocamaan.kenet.data.db.ContactEntity
import com.eneskocamaan.kenet.databinding.FragmentProfileSetupBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfileSetupFragment : Fragment(R.layout.fragment_profile_setup) {

    private var _binding: FragmentProfileSetupBinding? = null
    private val binding get() = _binding!!

    private val args: ProfileSetupFragmentArgs by navArgs()

    // Seçilen kişileri hafızada tutmak için liste
    private var selectedContactsList: List<ContactModel> = emptyList()

    // İzin isteme launcher'ı
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                navigateToContactSelection()
            } else {
                Toast.makeText(context, "Rehbere erişim izni reddedildi.", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentProfileSetupBinding.bind(view)

        setupBloodTypeDropdown()
        setupFragmentResultListener()

        // Kişi Seç Butonu
        binding.btnSelectContacts.setOnClickListener {
            checkPermissionAndOpenContacts()
        }

        // Kaydet Butonu
        binding.btnCompleteProfile.setOnClickListener {
            val displayName = binding.etDisplayName.text.toString().trim()
            val bloodType = binding.etBloodType.text.toString().trim()

            if (displayName.isNotEmpty()) {
                completeProfile(displayName, if (bloodType.isEmpty()) null else bloodType)
            } else {
                binding.tilDisplayName.error = "İsim alanı zorunludur"
            }
        }
    }

    private fun checkPermissionAndOpenContacts() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            navigateToContactSelection()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    private fun navigateToContactSelection() {
        val action = ProfileSetupFragmentDirections.actionProfileSetupFragmentToContactSelectionFragment()
        findNavController().navigate(action)
    }

    // Kişi seçme ekranından dönen veriyi dinle
    private fun setupFragmentResultListener() {
        setFragmentResultListener("requestKey_contacts") { _, bundle ->
            val contacts = bundle.getParcelableArrayList<ContactModel>("selected_contacts")
            if (contacts != null) {
                selectedContactsList = contacts
                // Buton yazısını güncelle
                binding.btnSelectContacts.text = if (contacts.isNotEmpty()) {
                    "${contacts.size} Kişi Seçildi"
                } else {
                    "Güvenilir Kişileri Seç"
                }
                // İkon güncellemesi
                binding.btnSelectContacts.setIconResource(
                    if (contacts.isNotEmpty()) R.drawable.ic_check_circle else R.drawable.ic_person
                )
            }
        }
    }

    private fun setupBloodTypeDropdown() {
        val bloodTypes = resources.getStringArray(R.array.blood_types_array)
        val adapter = ArrayAdapter(requireContext(), R.layout.dropdown_item, bloodTypes)
        (binding.etBloodType as? AutoCompleteTextView)?.setAdapter(adapter)
    }

    private fun completeProfile(displayName: String, bloodType: String?) {
        setLoading(true)
        lifecycleScope.launch {
            try {
                // 1. İsteği Hazırla
                val request = CompleteProfileRequest(
                    phoneNumber = args.phoneNumber,
                    displayName = displayName,
                    bloodType = bloodType,
                    contacts = selectedContactsList
                )

                // 2. API'ye Gönder
                val response = ApiClient.api.completeProfile(request)

                if (response.isSuccessful) {
                    // --- DÜZELTME BURADA YAPILDI ---
                    // args.userId 'null' gelirse, boş bir string ("") ata.
                    // Böylece türü 'String?' değil, 'String' olur ve hatalar gider.
                    val myUserId = args.userId ?: ""

                    // 3. Veritabanına Kaydet
                    if (myUserId.isNotEmpty()) {
                        saveToLocalDb(displayName, bloodType, myUserId)

                        Toast.makeText(context, "Kurulum Tamamlandı!", Toast.LENGTH_SHORT).show()

                        // 4. Ana Ekrana Git
                        val action = ProfileSetupFragmentDirections.actionProfileSetupFragmentToPeersFragment()
                        findNavController().navigate(action)
                    } else {
                        handleApiError("Kullanıcı ID hatası: ID bulunamadı.")
                    }
                } else {
                    handleApiError("Sunucu Hatası: ${response.errorBody()?.string()}")
                }
            } catch (e: Exception) {
                handleApiError("Bağlantı hatası: ${e.message}")
                e.printStackTrace()
            } finally {
                if (isAdded) setLoading(false)
            }
        }
    }

    private suspend fun saveToLocalDb(displayName: String, bloodType: String?, myUserId: String) {
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(requireContext())

            // A. Kullanıcı profilini güncelle
            db.userDao().updateUserProfile(args.phoneNumber, displayName, bloodType)

            // B. Seçilen kişileri contacts tablosuna kaydet
            if (selectedContactsList.isNotEmpty()) {
                val contactEntities = selectedContactsList.map { contact ->
                    ContactEntity(
                        ownerId = myUserId,
                        // --- DÜZELTME BURADA (camelCase) ---
                        contactPhoneNumber = contact.phoneNumber, // .phone_number YERİNE .phoneNumber
                        contactName = contact.displayName,       // .display_name YERİNE .displayName
                        // -----------------------------------
                        contactServerId = null
                    )
                }
                db.contactDao().insertContacts(contactEntities)
            }
        }
    }

    private fun handleApiError(message: String?) {
        if (!isAdded) return
        Toast.makeText(context, message ?: "Bir hata oluştu", Toast.LENGTH_LONG).show()
    }

    private fun setLoading(isLoading: Boolean) {
        if (_binding == null) return
        binding.btnCompleteProfile.isEnabled = !isLoading
        binding.btnSelectContacts.isEnabled = !isLoading
        binding.tilDisplayName.isEnabled = !isLoading
        binding.tilBloodType.isEnabled = !isLoading
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}