package com.eneskocamaan.kenet.peer

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.eneskocamaan.kenet.R
import com.eneskocamaan.kenet.data.db.MessageEntity

class MessageAdapter(
    private val messages: MutableList<MessageEntity>,
    private val myDeviceId: String // Hangi mesajın bana ait olduğunu anlamak için
) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    // Farklı görünüm tipleri
    private val VIEW_TYPE_MESSAGE_SENT = 1
    private val VIEW_TYPE_MESSAGE_RECEIVED = 2

    // Mesajın bana ait olup olmadığını kontrol et
    override fun getItemViewType(position: Int): Int {
        val message = messages[position]

        // Eğer mesajın gönderen ID'si benim ID'm ise, sağda göster (SENT).
        // NOT: MessageEntity modelimizde isSent flag'i de var, ancak bu kontrol daha güvenlidir.
        return if (message.senderId == myDeviceId) {
            VIEW_TYPE_MESSAGE_SENT
        } else {
            VIEW_TYPE_MESSAGE_RECEIVED
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val layoutRes = if (viewType == VIEW_TYPE_MESSAGE_SENT) {
            R.layout.item_message_sent // Sağda görünen layout (Gönderilen)
        } else {
            R.layout.item_message_received // Solda görünen layout (Alınan)
        }
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        holder.bind(message)
    }

    override fun getItemCount(): Int = messages.size

    // Yeni mesaj eklendiğinde listeyi güncelle
    fun addMessage(message: MessageEntity) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    // addmassage yerine tüm listeyi yeniler
    fun updateList(newMessages: List<MessageEntity>) {
        messages.clear()
        messages.addAll(newMessages)
        notifyDataSetChanged() // Tüm listeyi yeniler
    }

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val messageText: TextView = itemView.findViewById(R.id.text_message_body)

        fun bind(message: MessageEntity) {
            messageText.text = message.content
            messageText.setTextColor(Color.WHITE) // Yazı Rengi Beyaz
        }
    }
}