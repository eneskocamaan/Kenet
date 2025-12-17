package com.eneskocamaan.kenet.peer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.eneskocamaan.kenet.R
import com.eneskocamaan.kenet.data.db.MessageEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageAdapter(
    private val messages: MutableList<MessageEntity>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() { // Generic ViewHolder yaptık

    private val VIEW_TYPE_MESSAGE_SENT = 1
    private val VIEW_TYPE_MESSAGE_RECEIVED = 2

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isSent) {
            VIEW_TYPE_MESSAGE_SENT
        } else {
            VIEW_TYPE_MESSAGE_RECEIVED
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_MESSAGE_SENT) {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message_sent, parent, false)
            SentMessageViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message_received, parent, false)
            ReceivedMessageViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        if (holder is SentMessageViewHolder) {
            holder.bind(message)
        } else if (holder is ReceivedMessageViewHolder) {
            holder.bind(message)
        }
    }

    override fun getItemCount(): Int = messages.size

    fun updateList(newMessages: List<MessageEntity>) {
        messages.clear()
        messages.addAll(newMessages)
        notifyDataSetChanged()
    }

    // --- GİDEN MESAJ (Sağ Balon) ---
    class SentMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val messageText: TextView = itemView.findViewById(R.id.text_message_body)
        private val timeText: TextView = itemView.findViewById(R.id.text_message_time)
        private val statusIcon: ImageView = itemView.findViewById(R.id.image_message_status)

        fun bind(message: MessageEntity) {
            messageText.text = message.content
            timeText.text = formatTime(message.timestamp)

            // DURUM İKONU BELİRLEME
            // 0: Pending (Saat)
            // 1: Sent (Tek Tik)
            // 2: Delivered/Ack (Çift Tik)
            val iconRes = when (message.status) {
                0 -> R.drawable.ic_clock_gray
                1 -> R.drawable.ic_check_gray
                2 -> R.drawable.ic_double_check_blue
                else -> R.drawable.ic_clock_gray
            }
            statusIcon.setImageResource(iconRes)
        }
    }

    // --- GELEN MESAJ (Sol Balon) ---
    class ReceivedMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val messageText: TextView = itemView.findViewById(R.id.text_message_body)
        private val timeText: TextView = itemView.findViewById(R.id.text_message_time)

        fun bind(message: MessageEntity) {
            messageText.text = message.content
            timeText.text = formatTime(message.timestamp)
        }
    }

    companion object {
        fun formatTime(timestamp: Long): String {
            return if (timestamp > 0) {
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
            } else ""
        }
    }
}