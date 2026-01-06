package com.eneskocamaan.kenet.earthquake

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.eneskocamaan.kenet.data.api.ConfirmedEarthquakeItem
import com.eneskocamaan.kenet.databinding.ItemOfficialEarthquakeBinding

class OfficialEarthquakesAdapter(
    private val onClick: (ConfirmedEarthquakeItem) -> Unit
) : ListAdapter<ConfirmedEarthquakeItem, OfficialEarthquakesAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemOfficialEarthquakeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemOfficialEarthquakeBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ConfirmedEarthquakeItem) {
            binding.tvMag.text = item.magnitude.toString()
            binding.tvLocation.text = item.title

            // Backend'den gelen '2026-01-03T20:53:43' formatını temizliyoruz
            binding.tvDate.text = item.occurredAt.replace("T", " ").take(16)

            val colorCode = when {
                item.magnitude >= 5.0 -> "#FF453A"
                item.magnitude >= 4.0 -> "#FF9500"
                else -> "#32ADE6"
            }
            binding.tvMag.setTextColor(Color.parseColor(colorCode))
            binding.root.setOnClickListener { onClick(item) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ConfirmedEarthquakeItem>() {
        override fun areItemsTheSame(oldI: ConfirmedEarthquakeItem, newI: ConfirmedEarthquakeItem) = oldI.id == newI.id
        override fun areContentsTheSame(oldI: ConfirmedEarthquakeItem, newI: ConfirmedEarthquakeItem) = oldI == newI
    }
}