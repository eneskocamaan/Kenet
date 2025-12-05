package com.eneskocamaan.kenet.registration

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.eneskocamaan.kenet.R
import com.eneskocamaan.kenet.data.api.ApiClient
import com.eneskocamaan.kenet.data.api.SyncContactsRequest
import com.eneskocamaan.kenet.data.api.UpdateLocationRequest
import com.eneskocamaan.kenet.data.db.AppDatabase
import com.eneskocamaan.kenet.data.db.ContactEntity
import com.eneskocamaan.kenet.data.db.UserEntity
import com.eneskocamaan.kenet.databinding.FragmentSplashBinding
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class SplashFragment : Fragment(R.layout.fragment_splash) {

    private var _binding: FragmentSplashBinding? = null
    private val binding get() = _binding!!

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // GPS AÇMA İSTEĞİ SONUCU
    // Kullanıcı çıkan popup'ta "Tamam" dedi mi kontrol eder.
    private val resolutionForResult = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            // Kullanıcı GPS'i açtı, şimdi işlemlere başla
            Log.d("KENET_GPS", "Kullanıcı GPS'i açtı.")
            startAppLogic(hasPermission = true)
        } else {
            // Kullanıcı reddetti. Zorunlu olduğu için tekrar soruyoruz veya uyarıyoruz.
            Toast.makeText(requireContext(), "KENET'in çalışması için Konum Servisi (GPS) açık olmalıdır.", Toast.LENGTH_LONG).show()
            checkDeviceLocationSettings() // Tekrar döngüye sok (Zorunlu)
        }
    }

    // İZİN İSTEĞİ SONUCU
    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false)
        val coarseLocationGranted = permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false)

        if (fineLocationGranted || coarseLocationGranted) {
            // İzin alındı, şimdi GPS açık mı diye kontrol et
            checkDeviceLocationSettings()
        } else {
            // İzin verilmedi, tekrar iste veya uyarı göster
            Toast.makeText(requireContext(), "Uygulamanın çalışması için konum izni şarttır.", Toast.LENGTH_LONG).show()
            // İzin yoksa konumsuz devam etmek yerine tekrar isteyebilirsin ya da Login'e atarsın.
            // Şimdilik konumsuz devam ettiriyorum ama fonksiyon içinde GPS olmadığı için işlem yapmayacak.
            startAppLogic(hasPermission = false)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentSplashBinding.bind(view)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        startAnimations()

        lifecycleScope.launch {
            val currentUser = getCurrentUser()
            // Sadece giriş yapmış kullanıcılar için konum zorunlu
            if (currentUser != null) {
                checkPermissionsAndSettings()
            } else {
                // Giriş yapmamışsa direkt login ekranına git
                startAppLogic(hasPermission = false)
            }
        }
    }

    private fun checkPermissionsAndSettings() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            // İzin var, şimdi GPS açık mı kontrol et
            checkDeviceLocationSettings()
        } else {
            // İzin yok, iste
            locationPermissionRequest.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    /**
     * Cihazın GPS servisinin açık olup olmadığını kontrol eder.
     * Kapalıysa sistem dialog penceresini açar.
     */
    private fun checkDeviceLocationSettings() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000).build()
        val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest)
        val client = LocationServices.getSettingsClient(requireActivity())
        val task = client.checkLocationSettings(builder.build())

        task.addOnSuccessListener {
            // Her şey yolunda, GPS açık.
            Log.d("KENET_GPS", "GPS zaten açık.")
            startAppLogic(hasPermission = true)
        }

        task.addOnFailureListener { exception ->
            if (exception is ResolvableApiException) {
                // GPS kapalı ama açılabilir (Popup göster)
                try {
                    val intentSenderRequest = IntentSenderRequest.Builder(exception.resolution).build()
                    resolutionForResult.launch(intentSenderRequest)
                } catch (sendEx: IntentSender.SendIntentException) {
                    // Hata olursa yoksay
                    startAppLogic(hasPermission = false)
                }
            } else {
                // GPS açılamıyor (Cihaz desteklemiyor olabilir)
                startAppLogic(hasPermission = false)
            }
        }
    }

    private fun startAppLogic(hasPermission: Boolean) {
        lifecycleScope.launch {
            val minSplashTime = 2000L
            val startTime = System.currentTimeMillis()
            val currentUser = getCurrentUser()

            if (currentUser != null) {
                if (isInternetAvailable(requireContext())) {
                    if (hasPermission) {
                        // Burada "Tek Seferlik" konum alıyoruz.
                        // await() işlemi bitince konum almayı kendisi bırakır. Pil dostudur.
                        updateMyLocation(currentUser)
                    }
                    syncContactsFromServer(currentUser.userId)
                }
            }

            val elapsedTime = System.currentTimeMillis() - startTime
            if (elapsedTime < minSplashTime) {
                delay(minSplashTime - elapsedTime)
            }

            if (currentUser != null) {
                findNavController().navigate(R.id.action_splashFragment_to_peersFragment)
            } else {
                findNavController().navigate(R.id.action_splashFragment_to_loginFragment)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun updateMyLocation(user: UserEntity) {
        try {
            val cancellationTokenSource = CancellationTokenSource()

            // getCurrentLocation: Bu metod konumu BİR KERE alır ve servisi kapatır.
            // Sürekli dinleme yapmadığı için pil tüketimini durdurur.
            val location: Location? = fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cancellationTokenSource.token
            ).await()

            if (location != null) {
                Log.d("KENET_LOC", "Konum alındı ve işlem bitti: ${location.latitude}, ${location.longitude}")

                val db = AppDatabase.getDatabase(requireContext())
                val updatedUser = user.copy(
                    latitude = location.latitude,
                    longitude = location.longitude
                )
                db.userDao().insertUser(updatedUser)

                val request = UpdateLocationRequest(
                    user_id = user.userId,
                    latitude = location.latitude,
                    longitude = location.longitude
                )
                ApiClient.api.updateLocation(request)
            }
        } catch (e: Exception) {
            Log.e("KENET_LOC", "Konum hatası: ${e.message}")
        }
    }

    // ... (Diğer fonksiyonlar: syncContactsFromServer, getCurrentUser, isInternetAvailable, startAnimations aynı kalacak) ...

    private suspend fun syncContactsFromServer(myUserId: String) {
        withContext(Dispatchers.IO) {
            try {
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
                                contactServerId = dto.contact_id,
                                contactLatitude = dto.latitude,
                                contactLongitude = dto.longitude
                            )
                        }
                        db.contactDao().insertContacts(contactEntities)
                    }
                }
            } catch (e: Exception) {
                Log.e("KENET_SYNC", "Sync Hatası: ${e.message}")
            }
        }
    }

    private suspend fun getCurrentUser(): UserEntity? {
        return withContext(Dispatchers.IO) {
            AppDatabase.getDatabase(requireContext()).userDao().getUserProfile()
        }
    }

    private fun isInternetAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
        return activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private fun startAnimations() {
        binding.ivLogo.apply { alpha = 0f; translationY = 50f; animate().alpha(1f).translationY(0f).setDuration(800).setInterpolator(DecelerateInterpolator()).start() }
        binding.tvAppName.apply { alpha = 0f; translationY = 50f; animate().alpha(1f).translationY(0f).setStartDelay(200).setDuration(800).setInterpolator(DecelerateInterpolator()).start() }
        binding.tvSlogan.apply { alpha = 0f; translationY = 50f; animate().alpha(1f).translationY(0f).setStartDelay(400).setDuration(800).setInterpolator(DecelerateInterpolator()).start() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}