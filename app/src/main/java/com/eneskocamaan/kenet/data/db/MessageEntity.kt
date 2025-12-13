package com.eneskocamaan.kenet.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    val senderId: String,       // Gönderen Cihaz ID
    val receiverId: String,     // Alıcı Cihaz ID

    // Sohbet ekranında mesajları gruplamak için kullanılır.
    // Gelen mesajda -> senderId, Giden mesajda -> receiverId
    val chatPartnerId: String,

    val content: String,        // Mesaj Metni (Şimdilik açık metin)
    val timestamp: Long,
    val isSent: Boolean,        // True: Giden (Sağ), False: Gelen (Sol)
    val isRead: Boolean = false
)