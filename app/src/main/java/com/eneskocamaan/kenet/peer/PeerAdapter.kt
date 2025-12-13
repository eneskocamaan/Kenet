package com.eneskocamaan.kenet.peer

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.eneskocamaan.kenet.R
import com.eneskocamaan.kenet.data.db.ContactEntity
import com.eneskocamaan.kenet.data.db.ContactWithUnreadCount

class PeerAdapter(
    // TİP DEĞİŞTİ: Artık wrapper sınıfı alıyor
    private var contactsList: List<ContactWithUnreadCount>,
    private val onClick: (ContactEntity) -> Unit
) : RecyclerView.Adapter<PeerAdapter.PeerViewHolder>() {

    class PeerViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_contact_name)
        val tvAvatarLetter: TextView = view.findViewById(R.id.tv_avatar_letter)
        val tvKenetStatus: TextView = view.findViewById(R.id.tv_kenet_status)
        val tvLocationStatus: TextView = view.findViewById(R.id.tv_location_status)
        // YENİ: Bildirim Rozeti
        val tvBadge: TextView = view.findViewById(R.id.tv_unread_badge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PeerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_peer, parent, false)
        return PeerViewHolder(view)
    }

    override fun onBindViewHolder(holder: PeerViewHolder, position: Int) {
        // Wrapper'dan verileri ayır
        val item = contactsList[position]
        val contact = item.contact
        val unreadCount = item.unreadCount
        val context = holder.itemView.context

        // 1. Bildirim Rozeti Mantığı
        if (unreadCount > 0) {
            holder.tvBadge.text = unreadCount.toString()
            holder.tvBadge.visibility = View.VISIBLE
        } else {
            holder.tvBadge.visibility = View.GONE
        }

        // --- Diğer kodlar aynı, sadece 'contact' objesini kullanıyoruz ---

        holder.tvName.text = contact.contactName
        if (contact.contactName.isNotEmpty()) {
            holder.tvAvatarLetter.text = contact.contactName.first().uppercase()
        } else {
            holder.tvAvatarLetter.text = "?"
        }

        // Kenet Durumu
        if (!contact.contactServerId.isNullOrEmpty()) {
            holder.tvKenetStatus.text = "Kenet Ağı"
            holder.tvKenetStatus.setTextColor(Color.parseColor("#4CAF50"))
        } else {
            holder.tvKenetStatus.text = "Kenet Kullanmıyor"
            holder.tvKenetStatus.setTextColor(Color.parseColor("#cc4e4e"))
        }

        // Konum Durumu
        val lat = contact.contactLatitude
        val lng = contact.contactLongitude
        val hasLocation = (lat != null && lat != 0.0) && (lng != null && lng != 0.0)

        val dotDrawable = holder.tvLocationStatus.compoundDrawablesRelative[0]

        if (hasLocation) {
            holder.tvLocationStatus.text = "Konum Biliniyor"
            holder.tvLocationStatus.setTextColor(context.getColor(R.color.text_secondary))
            dotDrawable?.setTint(context.getColor(R.color.primary_color))
        } else {
            holder.tvLocationStatus.text = "Konum Bilinmiyor"
            holder.tvLocationStatus.setTextColor(Color.parseColor("#808080"))
            dotDrawable?.setTint(Color.parseColor("#606060"))
        }

        // Tıklama olayında orijinal 'contact' nesnesini gönder
        holder.itemView.setOnClickListener { onClick(contact) }
    }

    override fun getItemCount() = contactsList.size

    fun updateList(newContacts: List<ContactWithUnreadCount>) {
        this.contactsList = newContacts
        notifyDataSetChanged()
    }
}