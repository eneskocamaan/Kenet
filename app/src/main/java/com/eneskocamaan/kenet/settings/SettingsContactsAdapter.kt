package com.eneskocamaan.kenet.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.eneskocamaan.kenet.data.db.ContactEntity
import com.eneskocamaan.kenet.databinding.ItemContactSettingsBinding

class SettingsContactsAdapter(
    private val onDeleteClick: (ContactEntity) -> Unit
) : RecyclerView.Adapter<SettingsContactsAdapter.ViewHolder>() {

    private var items = listOf<ContactEntity>()

    fun submitList(newItems: List<ContactEntity>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemContactSettingsBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(val binding: ItemContactSettingsBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ContactEntity) {
            binding.tvName.text = item.contactName
            binding.tvPhone.text = item.contactPhoneNumber
            binding.ivDelete.setOnClickListener { onDeleteClick(item) }
        }
    }
}