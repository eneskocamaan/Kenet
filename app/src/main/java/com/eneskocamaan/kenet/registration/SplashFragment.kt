package com.eneskocamaan.kenet.registration

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.eneskocamaan.kenet.R
import com.eneskocamaan.kenet.data.api.ApiClient
import com.eneskocamaan.kenet.data.api.SyncContactsRequest
import com.eneskocamaan.kenet.data.db.AppDatabase
import com.eneskocamaan.kenet.data.db.ContactEntity
import com.eneskocamaan.kenet.data.db.UserEntity
import com.eneskocamaan.kenet.databinding.FragmentSplashBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SplashFragment : Fragment(R.layout.fragment_splash) {

    private var _binding: FragmentSplashBinding? = null
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentSplashBinding.bind(view)

        // Animasyonlar
        startAnimations()

        // İşlemleri Başlat
        lifecycleScope.launch {
            val minSplashTime = 2000L
            val startTime = System.currentTimeMillis()

            // 1. Yerel Veritabanından Kullanıcıyı Kontrol Et
            val currentUser = getCurrentUser()

            // 2. Senkronizasyon (Sadece İnternet Varsa ve Kullanıcı Giriş Yapmışsa)
            if (currentUser != null) {
                if (isInternetAvailable(requireContext())) {
                    // İnternet var: Sunucuyla konuş, ID'leri güncelle
                    syncContactsFromServer(currentUser.userId)
                } else {
                    // İnternet yok: Sadece log düş, işlem yapma
                    Log.d("KENET_SPLASH", "İnternet yok, çevrimdışı modda devam ediliyor.")
                }
            }

            // 3. Süreyi Tamamla
            val elapsedTime = System.currentTimeMillis() - startTime
            if (elapsedTime < minSplashTime) {
                delay(minSplashTime - elapsedTime)
            }

            // 4. Yönlendirme
            if (currentUser != null) {
                findNavController().navigate(R.id.action_splashFragment_to_peersFragment)
            } else {
                findNavController().navigate(R.id.action_splashFragment_to_loginFragment)
            }
        }
    }

    /**
     * İnternet bağlantısını kontrol eder.
     * Wi-Fi veya Mobil Veri fark etmeksizin internete çıkış var mı bakar.
     */
    private fun isInternetAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false

        return when {
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            else -> false
        }
    }

    private suspend fun getCurrentUser(): UserEntity? {
        return withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(requireContext())
            db.userDao().getUserProfile()
        }
    }

    private suspend fun syncContactsFromServer(myUserId: String) {
        withContext(Dispatchers.IO) {
            try {
                Log.d("KENET_SYNC", "Rehber senkronizasyonu başlatılıyor... UserID: $myUserId")

                val request = SyncContactsRequest(user_id = myUserId)
                val response = ApiClient.api.syncContacts(request)

                if (response.isSuccessful && response.body() != null) {
                    val serverContacts = response.body()!!.contacts
                    val db = AppDatabase.getDatabase(requireContext())

                    if (serverContacts.isNotEmpty()) {
                        val contactEntities = serverContacts.map { dto ->
                            ContactEntity(
                                ownerId = myUserId,
                                contactPhoneNumber = dto.phone_number,
                                contactName = dto.display_name,
                                contactServerId = dto.contact_id
                            )
                        }
                        db.contactDao().insertContacts(contactEntities)
                        Log.d("KENET_SYNC", "Senkronizasyon Başarılı: ${contactEntities.size} kişi.")
                    }
                } else {
                    Log.e("KENET_SYNC", "Senkronizasyon API Hatası: ${response.code()}")
                }
            } catch (e: Exception) {
                // Hata durumunda sessiz kal, uygulama akışını bozma
                Log.e("KENET_SYNC", "Bağlantı Hatası: ${e.message}")
            }
        }
    }

    private fun startAnimations() {
        binding.ivLogo.alpha = 0f
        binding.ivLogo.translationY = 50f
        binding.tvAppName.alpha = 0f
        binding.tvAppName.translationY = 50f
        binding.tvSlogan.alpha = 0f
        binding.tvSlogan.translationY = 50f

        binding.ivLogo.animate().alpha(1f).translationY(0f).setDuration(800).setInterpolator(DecelerateInterpolator()).start()
        binding.tvAppName.animate().alpha(1f).translationY(0f).setStartDelay(200).setDuration(800).setInterpolator(DecelerateInterpolator()).start()
        binding.tvSlogan.animate().alpha(1f).translationY(0f).setStartDelay(400).setDuration(800).setInterpolator(DecelerateInterpolator()).start()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}