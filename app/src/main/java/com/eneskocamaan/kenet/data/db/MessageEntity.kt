package com.eneskocamaan.kenet.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    val packetUid: String,
    val senderId: String,
    val receiverId: String,
    val chatPartnerId: String,
    val content: String,
    val timestamp: Long,

    val isSent: Boolean,

    // 0: PENDING (Bekliyor/Gönderilemedi - DTN)
    // 1: SENT (Ağa iletildi)
    // 2: DELIVERED (Karşı taraf aldı - ACK Geldi)
    val status: Int = 1,

    val isRead: Boolean = false // UI'da benim okumam
)