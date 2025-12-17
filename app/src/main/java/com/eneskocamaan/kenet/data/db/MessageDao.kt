package com.eneskocamaan.kenet.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chatPartnerId = :targetAddress ORDER BY timestamp ASC")
    fun getMessagesWith(targetAddress: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("UPDATE messages SET isRead = 1 WHERE chatPartnerId = :chatPartnerId AND isRead = 0 AND isSent = 0")
    suspend fun markMessagesAsRead(chatPartnerId: String)

    // ACK Geldiğinde durumu güncelle (SENT -> DELIVERED)
    @Query("UPDATE messages SET status = 2 WHERE packetUid = :packetUid")
    suspend fun markMessageAsDelivered(packetUid: String)

    // DTN: Bekleyen mesajları getir
    @Query("SELECT * FROM messages WHERE status = 0 AND isSent = 1")
    suspend fun getPendingMessages(): List<MessageEntity>

    // Mesaj iletildiğinde statusu güncelle (PENDING -> SENT)
    @Query("UPDATE messages SET status = 1 WHERE packetUid = :packetUid")
    suspend fun markMessageAsSent(packetUid: String)
}