package com.eneskocamaan.kenet.registration

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.eneskocamaan.kenet.R
import com.eneskocamaan.kenet.data.api.ApiClient
import com.eneskocamaan.kenet.data.db.AppDatabase
import com.eneskocamaan.kenet.data.db.UserEntity
import com.eneskocamaan.kenet.data.model.remote.request.RequestOtpRequest
import com.eneskocamaan.kenet.data.model.remote.response.VerifyOtpResponse
import com.eneskocamaan.kenet.databinding.FragmentLoginBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginFragment : Fragment(R.layout.fragment_login) {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dialog'dan gelecek sonucu dinle
        childFragmentManager.setFragmentResultListener(OtpVerificationDialogFragment.REQUEST_KEY, this) { _, bundle ->
            val response = bundle.getParcelable<VerifyOtpResponse>(OtpVerificationDialogFragment.RESPONSE_KEY)
            if (response != null) {
                handleVerificationResponse(response)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentLoginBinding.bind(view)

        binding.btnSendCode.setOnClickListener {
            val phoneNumber = binding.etPhoneNumber.text.toString()
            if (isValidPhoneNumber(phoneNumber)) {
                requestOtp(phoneNumber)
            } else {
                Toast.makeText(context, "Lütfen geçerli bir telefon numarası girin.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isValidPhoneNumber(phoneNumber: String): Boolean {
        return phoneNumber.length == 10 && phoneNumber.all { it.isDigit() }
    }

    private fun requestOtp(phoneNumber: String) {
        setLoading(true)
        lifecycleScope.launch {
            try {
                val response = ApiClient.api.requestOtp(RequestOtpRequest(phoneNumber))
                if (response.isSuccessful) {
                    setLoading(false)
                    Toast.makeText(context, response.body()?.message, Toast.LENGTH_SHORT).show()
                    // OTP doğrulama dialog'unu göster
                    OtpVerificationDialogFragment.newInstance(phoneNumber)
                        .show(childFragmentManager, OtpVerificationDialogFragment.TAG)
                } else {
                    handleApiError("OTP isteği başarısız oldu: ${response.errorBody()?.string()}")
                }
            } catch (e: Exception) {
                handleApiError(e.message)
            }
        }
    }

    private fun handleVerificationResponse(response: VerifyOtpResponse) {
        lifecycleScope.launch {
            // Gelen veriyi yerel veritabanına kaydet
            saveUserToLocalDb(response)

            if (response.isNewUser) {
                // Yeni Kullanıcı -> Profil tamamlama ekranına git
                val action = LoginFragmentDirections.actionLoginFragmentToProfileSetupFragment(response.phoneNumber, response.userId)
                findNavController().navigate(action)
            } else {
                // Mevcut Kullanıcı -> Ana ekrana git
                Toast.makeText(context, "Tekrar hoş geldin, ${response.displayName}!", Toast.LENGTH_LONG).show()
                findNavController().navigate(R.id.action_loginFragment_to_peersFragment)
            }
        }
    }
    
    private suspend fun saveUserToLocalDb(response: VerifyOtpResponse) {
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(requireContext())
            val user = UserEntity(
                userId = response.userId,
                phoneNumber = response.phoneNumber,
                displayName = response.displayName ?: "", // Henüz olmayabilir
                bloodType = response.bloodType,
                privateKey = response.ibePrivateKey,
                publicParams = response.publicParams
            )
            db.userDao().insertUser(user)
        }
    }

    private fun handleApiError(message: String?) {
        setLoading(false)
        Toast.makeText(context, "Bir hata oluştu: ${message ?: "Bilinmeyen hata"}", Toast.LENGTH_LONG).show()
    }

    private fun setLoading(isLoading: Boolean) {
        binding.btnSendCode.isEnabled = !isLoading
        binding.etPhoneNumber.isEnabled = !isLoading
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}