package com.eneskocamaan.kenet.registration

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.eneskocamaan.kenet.data.api.ApiClient
import com.eneskocamaan.kenet.data.api.VerifyOtpRequest
import com.eneskocamaan.kenet.databinding.DialogOtpVerificationBinding
import kotlinx.coroutines.launch

class OtpVerificationDialogFragment : DialogFragment() {

    private var _binding: DialogOtpVerificationBinding? = null
    private val binding get() = _binding!!

    private val phoneNumber by lazy { requireArguments().getString(ARG_PHONE_NUMBER)!! }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogOtpVerificationBinding.inflate(inflater, container, false)
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent) // Arka planı şeffaf yap
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        isCancelable = false // Kullanıcının dışarı tıklayarak kapatmasını engelle

        binding.tvDialogSubtitle.text = "+90 $phoneNumber numarasına gönderilen 4 haneli kodu girin."
        binding.btnVerify.setOnClickListener {
            val code = binding.etOtpCode.text.toString()
            if (code.length == 4) {
                verifyOtp(code)
            } else {
                Toast.makeText(context, "Lütfen 4 haneli kodu girin.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun verifyOtp(code: String) {
        setLoading(true)
        lifecycleScope.launch {
            try {
                val response = ApiClient.api.verifyOtp(VerifyOtpRequest(phoneNumber, code))
                if (response.isSuccessful && response.body() != null) {
                    // Başarılı olursa sonucu LoginFragment'a gönder
                    setFragmentResult(REQUEST_KEY, bundleOf(RESPONSE_KEY to response.body()))
                    dismiss() // Dialog'u kapat
                } else {
                    handleApiError("Doğrulama kodu hatalı.")
                }
            } catch (e: Exception) {
                handleApiError(e.message)
            }
        }
    }

    private fun setLoading(isLoading: Boolean) {
        binding.dialogProgressBar.isVisible = isLoading
        binding.btnVerify.isEnabled = !isLoading
        binding.etOtpCode.isEnabled = !isLoading
    }

    private fun handleApiError(message: String?) {
        setLoading(false)
        Toast.makeText(context, "Hata: ${message ?: "Bilinmeyen hata"}", Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "OtpVerificationDialog"
        const val REQUEST_KEY = "otp_verification_request"
        const val RESPONSE_KEY = "otp_verification_response"
        private const val ARG_PHONE_NUMBER = "phone_number"

        fun newInstance(phoneNumber: String): OtpVerificationDialogFragment {
            return OtpVerificationDialogFragment().apply {
                arguments = bundleOf(ARG_PHONE_NUMBER to phoneNumber)
            }
        }
    }
}