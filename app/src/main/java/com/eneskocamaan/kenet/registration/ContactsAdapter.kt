package com.eneskocamaan.kenet.registration

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.eneskocamaan.kenet.R
import com.eneskocamaan.kenet.data.api.ContactModel
import com.eneskocamaan.kenet.databinding.ItemContactBinding

class ContactsAdapter(
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<ContactsAdapter.ContactViewHolder>() {

    private var contacts = listOf<ContactModel>()

    fun submitList(list: List<ContactModel>) {
        contacts = list
        notifyDataSetChanged()
    }

    // Seçilenleri döndüren fonksiyon
    fun getSelectedContacts(): List<ContactModel> {
        return contacts.filter { it.isSelected }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val binding = ItemContactBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ContactViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        holder.bind(contacts[position])
    }

    override fun getItemCount() = contacts.size

    inner class ContactViewHolder(private val binding: ItemContactBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(contact: ContactModel) {
            binding.tvContactName.text = contact.display_name
            binding.tvContactPhone.text = contact.phone_number
            binding.cbSelect.isChecked = contact.isSelected

            // --- GÜNCELLENEN KISIM: KENET KULLANICI KONTROLÜ ---
            if (contact.isKenetUser) {
                // Kenet kullanıyorsa rozeti göster
                binding.tvAppUserBadge.visibility = View.VISIBLE
                // İsmi ana renk (Primary) yap
                binding.tvContactName.setTextColor(
                    ContextCompat.getColor(binding.root.context, R.color.primary_color)
                )
            } else {
                // Kullanmıyorsa rozeti gizle
                binding.tvAppUserBadge.visibility = View.GONE
                // İsmi varsayılan beyaz yap
                binding.tvContactName.setTextColor(
                    ContextCompat.getColor(binding.root.context, R.color.white)
                )
            }
            // ---------------------------------------------------

            // Satıra tıklama (Seçimi değiştir)
            binding.root.setOnClickListener {
                toggleSelection(contact)
            }

            // Checkbox tıklama
            binding.cbSelect.setOnClickListener {
                contact.isSelected = binding.cbSelect.isChecked
                onSelectionChanged(getSelectedContacts().size)
            }
        }

        private fun toggleSelection(contact: ContactModel) {
            contact.isSelected = !contact.isSelected
            binding.cbSelect.isChecked = contact.isSelected
            onSelectionChanged(getSelectedContacts().size)
        }
    }
}