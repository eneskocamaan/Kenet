package com.eneskocamaan.kenet.earthquake

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.eneskocamaan.kenet.R
import com.eneskocamaan.kenet.data.api.ApiClient
import com.eneskocamaan.kenet.databinding.FragmentOfficialEarthquakeBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class OfficialEarthquakeFragment : Fragment(R.layout.fragment_official_earthquake) {

    private var _binding: FragmentOfficialEarthquakeBinding? = null
    private val binding get() = _binding!!
    private var isAutoRefreshActive = true // Döndü kontrolü

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentOfficialEarthquakeBinding.bind(view)

        val adapter = OfficialEarthquakesAdapter { earthquake ->
            val sheet = EarthquakeDetailSheet(earthquake)
            sheet.show(parentFragmentManager, "EarthquakeDetail")
        }

        binding.rvOfficialEarthquakes.layoutManager = LinearLayoutManager(requireContext())
        binding.rvOfficialEarthquakes.adapter = adapter

        // 1. Manuel Yenileme (Aşağı Çekince)
        binding.swipeRefresh.setOnRefreshListener {
            fetchData(adapter, isManual = true)
        }

        // 2. Otomatik Yenileme Döngüsünü Başlat
        startAutoRefresh(adapter)
    }

    private fun startAutoRefresh(adapter: OfficialEarthquakesAdapter) {
        // lifecycleScope sayesinde sayfa kapanınca bu döngü otomatik durur.
        viewLifecycleOwner.lifecycleScope.launch {
            while (isAutoRefreshActive) {
                fetchData(adapter, isManual = false)
                delay(30000) // 30 saniyede bir veriyi tazele
            }
        }
    }

    private fun fetchData(adapter: OfficialEarthquakesAdapter, isManual: Boolean) {
        // Eğer kullanıcı manuel çekmediyse ve liste boşsa ProgressBar göster
        if (!isManual && adapter.itemCount == 0) {
            binding.progressBar.visibility = View.VISIBLE
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = ApiClient.api.getConfirmedEarthquakes()

                if (response.isSuccessful && response.body() != null) {
                    val list = response.body()!!.earthquakes
                    Log.d("KENET_DEBUG", "Güncel veri sayısı: ${list.size}")

                    // ListAdapter farkları otomatik hesaplar ve sadece değişeni günceller
                    adapter.submitList(list)
                }
            } catch (e: Exception) {
                Log.e("KENET_DEBUG", "Yenileme Hatası: ${e.message}")
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false // Swipe simgesini durdur
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        isAutoRefreshActive = false // Döngüyü kırmak için
        _binding = null
    }
}