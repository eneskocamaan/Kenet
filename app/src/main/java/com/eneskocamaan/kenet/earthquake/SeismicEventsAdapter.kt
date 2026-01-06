package com.eneskocamaan.kenet.earthquake

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.eneskocamaan.kenet.data.db.AppDetectedEventEntity
import com.eneskocamaan.kenet.databinding.ItemSeismicEventBinding
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class SeismicEventsAdapter : ListAdapter<AppDetectedEventEntity, SeismicEventsAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSeismicEventBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemSeismicEventBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: AppDetectedEventEntity) {
            // Başlık
            binding.tvLocationTitle.text = "KENET ALGILAMASI"

            // Şiddet Metni
            binding.tvIntensity.text = item.intensityLabel

            // Kullanıcı Sayısı
            binding.tvUserCount.text = "${item.participatingUsers} Cihaz Tarafından Doğrulandı"

            // --- SAAT DÜZELTME (UTC -> LOCAL) ---
            binding.tvDate.text = formatUtcToLocal(item.createdAt)

            // --- MODERN RENKLER ---
            // Pastel tonlar daha modern durur
            val colorCode = when {
                item.intensityLabel.contains("YIKICI") || item.intensityLabel.contains("EKSTREM") -> "#EF5350" // Kırmızı
                item.intensityLabel.contains("GÜÇLÜ") || item.intensityLabel.contains("ÇOK") -> "#FF7043" // Turuncu
                item.intensityLabel.contains("ORTA") -> "#FFA726" // Amber
                item.intensityLabel.contains("HAFİF") -> "#FFEE58" // Sarı
                else -> "#66BB6A" // Yeşil (Hissedilemez/Zayıf)
            }

            val color = Color.parseColor(colorCode)
            binding.viewSeverityIndicator.setBackgroundColor(color)
            binding.tvIntensity.setTextColor(color)
        }
    }

    // --- UTC SAATİNİ YERELE ÇEVİREN FONKSİYON ---
    private fun formatUtcToLocal(utcDateString: String): String {
        try {
            // Backend formatı: 2026-01-03T16:57:00.123456 (Mikrosaniyeler olabilir)
            // Basitlik için sadece ilk 19 karakteri alıyoruz (saniyeye kadar)
            val cleanDateString = if(utcDateString.length > 19) utcDateString.substring(0, 19) else utcDateString

            // 1. UTC Olarak Oku
            val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            inputFormat.timeZone = TimeZone.getTimeZone("UTC") // Gelen veri UTC

            val date = inputFormat.parse(cleanDateString)

            // 2. Yerel Saat Olarak Yaz
            val outputFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            outputFormat.timeZone = TimeZone.getDefault() // Telefonun saati (TR)

            return if (date != null) outputFormat.format(date) else utcDateString.substring(11, 16)
        } catch (e: Exception) {
            e.printStackTrace()
            // Hata olursa ham veriden saati kesip göster
            return if(utcDateString.length >= 16) utcDateString.substring(11, 16) else utcDateString
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<AppDetectedEventEntity>() {
        override fun areItemsTheSame(oldItem: AppDetectedEventEntity, newItem: AppDetectedEventEntity) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: AppDetectedEventEntity, newItem: AppDetectedEventEntity) = oldItem == newItem
    }
}