package com.eneskocamaan.kenet.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    // Belirli bir kişiyle olan mesajları zamana göre sıralı getir
    @Query("SELECT * FROM messages WHERE chatPartnerId = :targetAddress ORDER BY timestamp ASC")
    fun getMessagesWith(targetAddress: String): Flow<List<MessageEntity>>

    // Mesaj ekle (Çakışma olursa eskisiyle değiştir - REPLACE daha güvenlidir)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    // --- KRİTİK DÜZELTME BURADA ---
    // Sohbet açıldığında, sadece o kişiden 'GELEN' (isSent=0) ve 'OKUNMAMIŞ' (isRead=0)
    // mesajları okundu olarak işaretle. Kendi gönderdiklerimizi bozmaz.
    @Query("UPDATE messages SET isRead = 1 WHERE chatPartnerId = :chatPartnerId AND isRead = 0 AND isSent = 0")
    suspend fun markMessagesAsRead(chatPartnerId: String)

    // Tüm okunmamış GELEN mesajları getir (Bildirim rozeti için)
    @Query("SELECT * FROM messages WHERE isRead = 0 AND isSent = 0")
    fun getAllUnreadMessages(): Flow<List<MessageEntity>>
}