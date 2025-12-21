package com.eneskocamaan.kenet.registration

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.eneskocamaan.kenet.R
import com.eneskocamaan.kenet.data.api.ApiClient
import com.eneskocamaan.kenet.data.api.RequestOtpRequest
// [DÜZELTME] Doğru import kullanıldı
import com.eneskocamaan.kenet.data.api.VerifyOtpResponse
import com.eneskocamaan.kenet.data.db.AppDatabase
import com.eneskocamaan.kenet.data.db.UserEntity
import com.eneskocamaan.kenet.databinding.FragmentLoginBinding
import com.eneskocamaan.kenet.service.NetworkService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginFragment : Fragment(R.layout.fragment_login) {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // DialogFragment'tan gelen sonucu dinle
        childFragmentManager.setFragmentResultListener(OtpVerificationDialogFragment.REQUEST_KEY, this) { _, bundle ->

            val response: VerifyOtpResponse? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                bundle.getParcelable(OtpVerificationDialogFragment.RESPONSE_KEY, VerifyOtpResponse::class.java)
            } else {
                @Suppress("DEPRECATION")
                bundle.getParcelable(OtpVerificationDialogFragment.RESPONSE_KEY)
            }

            if (response != null) {
                handleVerificationResponse(response)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentLoginBinding.bind(view)

        binding.btnSendCode.setOnClickListener {
            val phoneNumber = binding.etPhoneNumber.text.toString().trim()
            if (isValidPhoneNumber(phoneNumber)) {
                requestOtp(phoneNumber)
            } else {
                Toast.makeText(context, "Lütfen geçerli bir telefon numarası girin.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isValidPhoneNumber(phoneNumber: String): Boolean {
        // Basit validasyon: 10 haneli olmalı
        return phoneNumber.length == 10 && phoneNumber.all { it.isDigit() }
    }

    private fun requestOtp(phoneNumber: String) {
        setLoading(true)
        lifecycleScope.launch {
            try {
                val response = ApiClient.api.requestOtp(RequestOtpRequest(phoneNumber))
                if (response.isSuccessful) {
                    setLoading(false)
                    Toast.makeText(context, response.body()?.message ?: "Kod gönderildi", Toast.LENGTH_SHORT).show()

                    // DialogFragment'ı aç
                    OtpVerificationDialogFragment.newInstance(phoneNumber)
                        .show(childFragmentManager, OtpVerificationDialogFragment.TAG)
                } else {
                    handleApiError("OTP isteği başarısız: ${response.code()}")
                }
            } catch (e: Exception) {
                handleApiError("Bağlantı hatası: ${e.message}")
            } finally {
                // Hata durumunda loading'i kapat
                if (_binding != null && binding.btnSendCode.isEnabled.not()) {
                    // setLoading(false) gerekebilir ama zaten handleApiError içinde var
                }
            }
        }
    }

    private fun handleVerificationResponse(response: VerifyOtpResponse) {
        lifecycleScope.launch {
            // Null Safety: Gelen veriler null olsa bile varsayılan değerlerle kaydet
            saveUserToLocalDb(response)

            // isNewUser null gelebilir, false varsayalım
            if (response.isNewUser == true) {
                // YENİ KULLANICI -> Profil Kurulumuna Git
                val phone = response.phoneNumber ?: ""
                val uid = response.userId ?: ""

                val action = LoginFragmentDirections.actionLoginFragmentToProfileSetupFragment(phone, uid)
                findNavController().navigate(action)
            } else {
                // MEVCUT KULLANICI -> Ana Ekrana Git
                startNetworkService()
                val name = response.displayName ?: "Kullanıcı"
                Toast.makeText(context, "Tekrar hoş geldin, $name!", Toast.LENGTH_LONG).show()
                findNavController().navigate(R.id.action_loginFragment_to_peersFragment)
            }
        }
    }

    private fun startNetworkService() {
        try {
            val intent = Intent(requireContext(), NetworkService::class.java)
            requireContext().startService(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun saveUserToLocalDb(response: VerifyOtpResponse) {
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(requireContext())
            // Response'dan gelen null değerleri güvenli hale getiriyoruz
            val user = UserEntity(
                userId = response.userId ?: "unknown_id",
                phoneNumber = response.phoneNumber ?: "0000000000",
                displayName = response.displayName ?: "",
                bloodType = response.bloodType,
                ibePrivateKey = response.ibePrivateKey ?: "",
                publicParams = response.publicParams ?: ""
            )
            db.userDao().insertUser(user)
        }
    }

    private fun handleApiError(message: String?) {
        setLoading(false)
        Toast.makeText(context, message ?: "Bilinmeyen hata", Toast.LENGTH_LONG).show()
    }

    private fun setLoading(isLoading: Boolean) {
        if (_binding != null) {
            binding.btnSendCode.isEnabled = !isLoading
            binding.etPhoneNumber.isEnabled = !isLoading
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}