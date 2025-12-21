package com.eneskocamaan.kenet.registration

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
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
import com.eneskocamaan.kenet.data.api.CheckContactsRequest
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

    private val resolutionForResult = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            startAppLogic(hasPermission = true)
        } else {
            Toast.makeText(requireContext(), "Konum servisi gereklidir.", Toast.LENGTH_LONG).show()
            checkDeviceLocationSettings()
        }
    }

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false) ||
                permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false)
        if (granted) checkDeviceLocationSettings() else startAppLogic(hasPermission = false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentSplashBinding.bind(view)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        startAnimations()

        lifecycleScope.launch {
            val currentUser = getCurrentUser()
            if (currentUser != null) checkPermissionsAndSettings()
            else startAppLogic(hasPermission = false)
        }
    }

    private fun checkPermissionsAndSettings() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            checkDeviceLocationSettings()
        } else {
            locationPermissionRequest.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    private fun checkDeviceLocationSettings() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000).build()
        val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest)
        val client = LocationServices.getSettingsClient(requireActivity())

        client.checkLocationSettings(builder.build())
            .addOnSuccessListener { startAppLogic(hasPermission = true) }
            .addOnFailureListener { exception ->
                if (exception is ResolvableApiException) {
                    try { resolutionForResult.launch(IntentSenderRequest.Builder(exception.resolution).build()) }
                    catch (e: Exception) { startAppLogic(hasPermission = false) }
                } else startAppLogic(hasPermission = false)
            }
    }

    private fun startAppLogic(hasPermission: Boolean) {
        lifecycleScope.launch {
            val minSplashTime = 2000L
            val startTime = System.currentTimeMillis()
            val currentUser = getCurrentUser()

            if (currentUser != null) {
                if (isInternetAvailable(requireContext())) {
                    if (hasPermission) updateMyLocation(currentUser)

                    // [ÖNEMLİ] Hibrit Senkronizasyon Başlatılıyor
                    fullSyncContactDetails(currentUser.userId)
                }
            }

            val elapsedTime = System.currentTimeMillis() - startTime
            if (elapsedTime < minSplashTime) delay(minSplashTime - elapsedTime)

            val actionId = if (currentUser != null) R.id.action_splashFragment_to_peersFragment else R.id.action_splashFragment_to_loginFragment
            findNavController().navigate(actionId)
        }
    }

    private suspend fun fullSyncContactDetails(myUserId: String) {
        withContext(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(requireContext())

                // 1. ADIM: 'sync_contacts' ile sunucuda zaten kayıtlı olanları hızlıca çek
                try {
                    val syncReq = SyncContactsRequest(myUserId)
                    val syncRes = ApiClient.api.syncContacts(syncReq)
                    if (syncRes.isSuccessful && syncRes.body() != null) {
                        val newContacts = syncRes.body()!!.contacts.map { dto ->
                            ContactEntity(
                                ownerId = myUserId,
                                contactPhoneNumber = dto.phoneNumber,
                                contactName = dto.displayName,
                                contactServerId = dto.contactId,
                                contactDisplayName = dto.displayName,
                                contactLatitude = dto.latitude,
                                contactLongitude = dto.longitude,
                                ibePublicKey = dto.publicKey
                            )
                        }
                        if(newContacts.isNotEmpty()) {
                            db.contactDao().insertContacts(newContacts)
                        }
                    }
                } catch (e: Exception) { Log.e("KENET", "Basic Sync Fail: ${e.message}") }

                // 2. ADIM: Yerel rehberdeki TÜM numaraları al ve sunucuya sor (Detay Güncelleme)
                val allNumbers = db.contactDao().getAllPhoneNumbers(myUserId)
                if (allNumbers.isNotEmpty()) {
                    val checkReq = CheckContactsRequest(allNumbers)
                    val checkRes = ApiClient.api.checkContacts(checkReq)

                    if (checkRes.isSuccessful && checkRes.body() != null) {
                        val serverUsers = checkRes.body()!!.registeredUsers
                        val updates = mutableListOf<ContactEntity>()

                        for (sUser in serverUsers) {
                            val local = db.contactDao().getContactByPhone(myUserId, sUser.phoneNumber)
                            if (local != null) {
                                // Sunucudan gelen taze verilerle güncelle
                                val updated = local.copy(
                                    contactServerId = sUser.userId,
                                    contactDisplayName = sUser.displayName,
                                    bloodType = sUser.bloodType,
                                    ibePublicKey = sUser.publicKey,
                                    contactLatitude = sUser.latitude,
                                    contactLongitude = sUser.longitude
                                )
                                updates.add(updated)
                            }
                        }
                        if (updates.isNotEmpty()) {
                            db.contactDao().insertContacts(updates)
                            Log.d("KENET_SYNC", "${updates.size} kişi detaylı güncellendi.")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("KENET_SYNC", "Full Sync Error: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun updateMyLocation(user: UserEntity) {
        try {
            val location: Location? = fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token
            ).await()

            if (location != null) {
                val db = AppDatabase.getDatabase(requireContext())
                db.userDao().insertUser(user.copy(latitude = location.latitude, longitude = location.longitude))
                ApiClient.api.updateLocation(UpdateLocationRequest(user.userId, location.latitude, location.longitude))
            }
        } catch (e: Exception) { Log.e("KENET_LOC", "Loc Error: ${e.message}") }
    }

    private suspend fun getCurrentUser() = withContext(Dispatchers.IO) {
        AppDatabase.getDatabase(requireContext()).userDao().getUserProfile()
    }

    private fun isInternetAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
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