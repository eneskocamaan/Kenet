package com.eneskocamaan.kenet.peer

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.eneskocamaan.kenet.R
import com.eneskocamaan.kenet.data.db.MessageEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageAdapter(
    private val messages: MutableList<MessageEntity>
    // currentUserId parametresini kaldırdık, çünkü isSent flag'ini kullanacağız.
) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    private val VIEW_TYPE_MESSAGE_SENT = 1
    private val VIEW_TYPE_MESSAGE_RECEIVED = 2

    override fun getItemViewType(position: Int): Int {
        val message = messages[position]

        // DÜZELTME BURADA:
        // ID karşılaştırması yerine veritabanındaki kesin bilgiye güveniyoruz.
        // isSent = true ise mesajı biz göndermişizdir -> SAĞDA GÖSTER
        return if (message.isSent) {
            VIEW_TYPE_MESSAGE_SENT
        } else {
            VIEW_TYPE_MESSAGE_RECEIVED
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val layoutRes = if (viewType == VIEW_TYPE_MESSAGE_SENT) {
            R.layout.item_message_sent     // Sağ Balon
        } else {
            R.layout.item_message_received // Sol Balon
        }
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount(): Int = messages.size

    fun updateList(newMessages: List<MessageEntity>) {
        messages.clear()
        messages.addAll(newMessages)
        notifyDataSetChanged()
    }

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val messageText: TextView = itemView.findViewById(R.id.text_message_body)
        private val timeText: TextView = itemView.findViewById(R.id.text_message_time)

        fun bind(message: MessageEntity) {
            // Mesaj İçeriği
            messageText.text = message.content
            messageText.setTextColor(Color.WHITE)

            // Saat Gösterimi (Timestamp -> HH:mm)
            if (message.timestamp > 0) {
                val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
                val timeString = sdf.format(Date(message.timestamp))
                timeText.text = timeString
                timeText.visibility = View.VISIBLE
            } else {
                timeText.text = ""
                timeText.visibility = View.GONE
            }
        }
    }
}