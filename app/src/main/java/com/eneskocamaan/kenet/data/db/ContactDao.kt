package com.eneskocamaan.kenet.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContacts(contacts: List<ContactEntity>)

    @Query("SELECT * FROM contacts WHERE ownerId = :ownerId")
    suspend fun getContacts(ownerId: String): List<ContactEntity>

    // PeersFragment için (Sadece liste)
    @Query("SELECT * FROM contacts")
    fun getAllContacts(): Flow<List<ContactEntity>>

    // PeersFragment için (Bildirim Rozetli Liste)
    @Query("""
        SELECT c.*, 
        (
            SELECT COUNT(*) FROM messages m 
            WHERE (m.chatPartnerId = c.contactServerId OR m.chatPartnerId = c.contactPhoneNumber)
            AND m.isRead = 0 
            AND m.isSent = 0
        ) as unreadCount 
        FROM contacts c
    """)
    fun getContactsWithUnreadCounts(): Flow<List<ContactWithUnreadCount>>

    // --- EKSİK OLAN PARÇA BU ---
    // ChatDetailFragment'ta kişiyi CANLI izlemek için (Konum gelince UI açılması için)
    @Query("SELECT * FROM contacts WHERE contactServerId = :id OR contactPhoneNumber = :id LIMIT 1")
    fun getContactByIdFlow(id: String): Flow<ContactEntity?>

    // Tek seferlik veri çekmek için
    @Query("SELECT * FROM contacts WHERE contactServerId = :id OR contactPhoneNumber = :id LIMIT 1")
    suspend fun getContactById(id: String): ContactEntity?

    @Query("DELETE FROM contacts WHERE ownerId = :ownerId AND contactPhoneNumber = :phoneNumber")
    suspend fun deleteContact(ownerId: String, phoneNumber: String)

    // Konum güncelleme (SocketManager kullanır)
    @Query("UPDATE contacts SET contactLatitude = :lat, contactLongitude = :lng WHERE contactServerId = :id OR contactPhoneNumber = :id")
    suspend fun updateContactLocation(id: String, lat: Double, lng: Double)
}