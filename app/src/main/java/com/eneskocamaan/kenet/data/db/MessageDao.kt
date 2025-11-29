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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("UPDATE messages SET isRead = 1 WHERE chatPartnerId = :partnerId AND isRead = 0")
    suspend fun markMessagesAsRead(partnerId: String)

    // YENİ: Okunmamış tüm mesajları getir (Flow ile canlı takip)
    // PeersFragment bunu dinleyecek ve rozetleri güncelleyecek.
    @Query("SELECT * FROM messages WHERE isRead = 0")
    fun getAllUnreadMessages(): Flow<List<MessageEntity>>
}