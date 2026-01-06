package com.eneskocamaan.kenet.earthquake

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

class EarthquakePagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {

    override fun getItemCount(): Int = 2 // İki sekmemiz var

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> SeismicNetworkFragment() // İlk sırada Kenet Ağı
            1 -> OfficialEarthquakeFragment()   // İkinci sırada Resmi Veriler
            else -> SeismicNetworkFragment()
        }
    }
}