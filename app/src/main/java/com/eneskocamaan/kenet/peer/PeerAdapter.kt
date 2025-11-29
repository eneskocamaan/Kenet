package com.eneskocamaan.kenet

import android.net.wifi.p2p.WifiP2pDevice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class PeerAdapter(
    private val peers: List<WifiP2pDevice>,
    // YENİ EKLENEN: Hangi cihazda kaç mesaj var haritası (MAC Adresi -> Sayı)
    private val unreadCounts: Map<String, Int>,
    private val onClick: (WifiP2pDevice) -> Unit
) : RecyclerView.Adapter<PeerAdapter.PeerViewHolder>() {

    class PeerViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvDeviceName: TextView = view.findViewById(R.id.tv_device_name)
        val tvDeviceAddress: TextView = view.findViewById(R.id.tv_device_address)
        // YENİ EKLENEN: Bildirim Rozeti (item_peer.xml içindeki ID)
        val tvBadge: TextView = view.findViewById(R.id.tv_badge_count)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PeerViewHolder {
        // item_peer layout'unu kullanıyoruz
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_peer, parent, false)
        return PeerViewHolder(view)
    }

    override fun onBindViewHolder(holder: PeerViewHolder, position: Int) {
        val device = peers[position]
        holder.tvDeviceName.text = device.deviceName

        // Cihaz Durumunu Türkçe Yazdırma
        val statusText = when(device.status) {
            WifiP2pDevice.AVAILABLE -> "Bağlanmaya Hazır"
            WifiP2pDevice.INVITED -> "Davet Gönderildi..."
            WifiP2pDevice.CONNECTED -> "Bağlı"
            WifiP2pDevice.FAILED -> "Hata"
            WifiP2pDevice.UNAVAILABLE -> "Ulaşılamıyor"
            else -> "Bilinmiyor"
        }
        holder.tvDeviceAddress.text = statusText

        // --- ROZET (BADGE) MANTIĞI ---
        // Bu cihazın adresine (MAC) ait okunmamış mesaj sayısını al
        val count = unreadCounts[device.deviceAddress] ?: 0

        if (count > 0) {
            // Mesaj varsa sayıyı yaz ve görünür yap
            holder.tvBadge.text = count.toString()
            holder.tvBadge.visibility = View.VISIBLE
        } else {
            // Mesaj yoksa gizle
            holder.tvBadge.visibility = View.GONE
        }

        // Tıklama olayını tetikle
        holder.itemView.setOnClickListener { onClick(device) }
    }

    override fun getItemCount() = peers.size
}