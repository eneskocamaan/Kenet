package com.eneskocamaan.kenet.settings

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.eneskocamaan.kenet.R
import com.eneskocamaan.kenet.data.api.ApiClient
import com.eneskocamaan.kenet.data.api.CompleteProfileRequest
import com.eneskocamaan.kenet.data.db.AppDatabase
import com.eneskocamaan.kenet.data.db.UserEntity

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

        setupUI() // Dropdown Kurulumu
        loadUserData()

        binding.btnUpdateProfile.setOnClickListener {
            val name = binding.etDisplayName.text.toString().trim()
            val blood = binding.etBloodType.text.toString().trim()
            if (name.isNotEmpty()) {
                updateProfile(name, blood)
            } else {
                binding.tilDisplayName.error = "İsim alanı boş olamaz"
            }
        }

        binding.btnManageContacts.setOnClickListener {
            findNavController().navigate(R.id.action_settingsFragment_to_trustedContactsFragment)
        }
    }

    // Dropdown listesinin kaybolmaması için onResume'da tekrar kuruyoruz
    override fun onResume() {
        super.onResume()
        setupUI()
    }

    private fun setupUI() {
        // Kan grubu listesini strings.xml'den çek
        val bloodTypes = resources.getStringArray(R.array.blood_types_array)

        // Adapter Oluştur
        val adapter = ArrayAdapter(requireContext(), R.layout.dropdown_item, bloodTypes)

        // AutoCompleteTextView'a Bağla
        (binding.etBloodType as? AutoCompleteTextView)?.setAdapter(adapter)

        // Tıklayınca listenin açılmasını garantile (Klavye yerine liste açılır)
        binding.etBloodType.setOnClickListener {
            (binding.etBloodType as? AutoCompleteTextView)?.showDropDown()
        }
    }

    private fun loadUserData() {
        lifecycleScope.launch {
            currentUser = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(requireContext()).userDao().getUserProfile()
            }

            currentUser?.let { user ->
                binding.etDisplayName.setText(user.displayName)
                // Veritabanındaki kan grubu değerini set et, ama filtreleme yapma (false)
                binding.etBloodType.setText(user.bloodType, false)
            }
        }
    }

    private fun updateProfile(name: String, blood: String) {
        val phone = currentUser?.phoneNumber ?: return
        setLoading(true)

        lifecycleScope.launch {
            try {
                val request = CompleteProfileRequest(phone, name, blood, emptyList())
                val response = ApiClient.api.completeProfile(request)

                if (response.isSuccessful) {
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