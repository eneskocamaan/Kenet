package com.eneskocamaan.kenet.earthquake

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.eneskocamaan.kenet.R
import com.eneskocamaan.kenet.data.api.ApiClient
import com.eneskocamaan.kenet.data.db.AppDatabase
import com.eneskocamaan.kenet.data.db.AppDetectedEventEntity
import com.eneskocamaan.kenet.databinding.FragmentSeismicNetworkBinding
import com.eneskocamaan.kenet.service.SeismicService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SeismicNetworkFragment : Fragment(R.layout.fragment_seismic_network) {

    private var _binding: FragmentSeismicNetworkBinding? = null
    private val binding get() = _binding!!

    private val adapter = SeismicEventsAdapter()
    private var isServiceRunning = false
    private var pollingJob: Job? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val notifGranted = permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
        val locGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false

        if (notifGranted && locGranted) {
            startSensorService()
        } else {
            Toast.makeText(context, "İzinler eksik, servis başlatılamadı.", Toast.LENGTH_LONG).show()
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (_binding == null) return
            if (intent?.action == "com.eneskocamaan.kenet.ACTION_STATUS_UPDATE") {
                val status = intent.getStringExtra("extra_status") ?: "Bilinmiyor"
                updateStatusUI(status)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentSeismicNetworkBinding.bind(view)

        binding.rvLocalEvents.layoutManager = LinearLayoutManager(requireContext())
        binding.rvLocalEvents.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener {
            refreshDataFromApi(isManual = true)
        }

        checkServiceState()
        binding.btnToggleService.setOnClickListener {
            if (isServiceRunning) stopSensorService() else checkPermissionsAndStart()
        }

        observeDatabase()
        startAutoPolling()
    }

    private fun observeDatabase() {
        val db = AppDatabase.getDatabase(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            db.appDetectedEventDao().getAllEvents().collectLatest { list ->
                if (_binding != null) {
                    adapter.submitList(list)
                    if (list.isNotEmpty()) binding.rvLocalEvents.smoothScrollToPosition(0)
                }
            }
        }
    }

    private fun startAutoPolling() {
        pollingJob?.cancel()
        pollingJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                refreshDataFromApi(isManual = false)
                delay(15000)
            }
        }
    }

    private fun refreshDataFromApi(isManual: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = ApiClient.api.getAppDetectedEvents()
                if (response.isSuccessful && response.body() != null) {
                    val apiList = response.body()!!
                    if (apiList.isNotEmpty()) {
                        val db = AppDatabase.getDatabase(requireContext())
                        val entities = apiList.map {
                            AppDetectedEventEntity(
                                it.id, it.latitude, it.longitude,
                                it.intensityLabel, it.maxPga,
                                it.participatingUsers, it.createdAt
                            )
                        }
                        db.appDetectedEventDao().insertAll(entities)
                    }
                }
            } catch (e: Exception) {
                Log.e("KENET_API", "Veri çekme hatası: ${e.message}")
            } finally {
                _binding?.swipeRefresh?.isRefreshing = false
            }
        }
    }

    private fun checkServiceState() {
        val prefs = requireContext().getSharedPreferences("seismic_prefs", Context.MODE_PRIVATE)
        isServiceRunning = prefs.getBoolean("is_service_running", false)
        updateButtonState(isServiceRunning)
    }

    private fun checkPermissionsAndStart() {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            startSensorService()
        }
    }

    private fun startSensorService() {
        val intent = Intent(requireContext(), SeismicService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(intent)
        } else {
            requireContext().startService(intent)
        }
        isServiceRunning = true
        updateButtonState(true)
    }

    private fun stopSensorService() {
        val intent = Intent(requireContext(), SeismicService::class.java)
        requireContext().stopService(intent)
        isServiceRunning = false
        updateButtonState(false)
    }

    private fun updateButtonState(running: Boolean) {
        if (_binding == null) return
        if (running) {
            binding.btnToggleService.text = "DURDUR"
            binding.btnToggleService.setTextColor(Color.RED)
            binding.btnToggleService.strokeColor = android.content.res.ColorStateList.valueOf(Color.RED)
        } else {
            binding.btnToggleService.text = "BAŞLAT"
            binding.btnToggleService.setTextColor(Color.parseColor("#4CAF50"))
            binding.btnToggleService.strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50"))
            updateStatusUI("Kapalı")
        }
    }

    private fun updateStatusUI(status: String) {
        if (_binding == null) return
        binding.tvStatusHeader.text = "DURUM: $status"
        val color = when {
            status.contains("Aktif") -> Color.GREEN
            status.contains("Kalibrasyon") -> Color.parseColor("#FFA500")
            status.contains("SARSINTI") -> Color.RED
            else -> Color.GRAY
        }
        binding.tvStatusHeader.setTextColor(color)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("com.eneskocamaan.kenet.ACTION_STATUS_UPDATE")

        // HATAYI DÜZELTEN KISIM BURASI:
        when {
            // Android 13 (API 33) ve üstü: RECEIVER_NOT_EXPORTED flag'i zorunlu
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                requireContext().registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            }
            // Android 8.0 (API 26) ile Android 12 arası: 3 parametreli metod var ama flag 0 olabilir
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                requireContext().registerReceiver(statusReceiver, filter, 0)
            }
            // API 24 ve 25: Sadece 2 parametreli metod destekleniyor
            else -> {
                requireContext().registerReceiver(statusReceiver, filter)
            }
        }

        checkServiceState()
    }

    override fun onPause() {
        super.onPause()
        try { requireContext().unregisterReceiver(statusReceiver) } catch (e: Exception) {}
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pollingJob?.cancel()
        _binding = null
    }
}