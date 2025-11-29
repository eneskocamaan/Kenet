package com.eneskocamaan.kenet.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    // Her satır için benzersiz, otomatik artan ID (Room yönetir)
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    // Mesajın kimden geldiği (Cihaz ID'si / MAC Adresi)
    val senderId: String,

    // Mesajın kime gittiği
    val receiverId: String,

    // KRİTİK ALAN: Bu mesaj hangi sohbet penceresine ait?
    // Gelen mesajsa -> Gönderen kişi Chat Partner'dır.
    // Giden mesajsa -> Alıcı kişi Chat Partner'dır.
    // DAO'daki sorgular bu alana göre çalışır.
    val chatPartnerId: String,

    val content: String,        // Mesaj içeriği
    val timestamp: Long,        // Gönderilme zamanı (Sıralama için)
    val isSent: Boolean,        // True: Sağda göster (Ben), False: Solda göster (O)
    val isRead: Boolean = false // Mesaj okundu mu?
)