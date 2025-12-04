package com.eneskocamaan.kenet.settings

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.eneskocamaan.kenet.R
import com.eneskocamaan.kenet.data.api.ApiClient
import com.eneskocamaan.kenet.data.db.AppDatabase
import com.eneskocamaan.kenet.data.db.UserEntity
import com.eneskocamaan.kenet.data.model.remote.request.CompleteProfileRequest
import com.eneskocamaan.kenet.databinding.FragmentSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private var currentUser: UserEntity? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentSettingsBinding.bind(view)

        setupUI()
        loadUserData()

        // 1. Profil Güncelle Butonu
        binding.btnUpdateProfile.setOnClickListener {
            val name = binding.etDisplayName.text.toString().trim()
            val blood = binding.etBloodType.text.toString().trim()
            if (name.isNotEmpty()) {
                updateProfile(name, blood)
            } else {
                binding.tilDisplayName.error = "İsim alanı boş olamaz"
            }
        }

        // 2. Kişileri Yönet Butonu -> TrustedContactsFragment'a Yönlendir
        // Artık liste burada değil, yeni sayfada.
        binding.btnManageContacts.setOnClickListener {
            // NavGraph'ta action_settingsFragment_to_trustedContactsFragment tanımlı olmalı
            findNavController().navigate(R.id.action_settingsFragment_to_trustedContactsFragment)
        }
    }

    private fun setupUI() {
        // Kan grubu listesini dropdown'a bağla
        val bloodTypes = resources.getStringArray(R.array.blood_types_array)
        val adapter = ArrayAdapter(requireContext(), R.layout.dropdown_item, bloodTypes)
        binding.etBloodType.setAdapter(adapter)
    }

    private fun loadUserData() {
        lifecycleScope.launch {
            currentUser = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(requireContext()).userDao().getUserProfile()
            }

            currentUser?.let { user ->
                binding.etDisplayName.setText(user.displayName)
                binding.etBloodType.setText(user.bloodType)
            }
        }
    }

    private fun updateProfile(name: String, blood: String) {
        val phone = currentUser?.phoneNumber ?: return
        setLoading(true)

        lifecycleScope.launch {
            try {
                // Backend Update: Sadece profil bilgilerini güncelle, kontak listesini boş gönder
                val request = CompleteProfileRequest(phone, name, blood, emptyList())
                val response = ApiClient.api.completeProfile(request)

                if (response.isSuccessful) {
                    // Local DB Update
                    withContext(Dispatchers.IO) {
                        AppDatabase.getDatabase(requireContext()).userDao().updateUserProfile(phone, name, blood)
                    }
                    Toast.makeText(context, "Profil güncellendi.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Hata: ${response.message()}", Toast.LENGTH_SHORT).show()
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
            binding.btnUpdateProfile.isEnabled = !isLoading
            binding.btnManageContacts.isEnabled = !isLoading
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}