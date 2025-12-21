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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: ContactEntity)

    // --- İŞTE EKSİK OLAN VE HATAYI ÇÖZEN FONKSİYON ---
    @Query("SELECT * FROM contacts WHERE ownerId = :ownerId")
    suspend fun getContacts(ownerId: String): List<ContactEntity>
    // --------------------------------------------------

    @Query("SELECT contactPhoneNumber FROM contacts WHERE ownerId = :ownerId")
    suspend fun getAllPhoneNumbers(ownerId: String): List<String>

    @Query("SELECT * FROM contacts WHERE ownerId = :ownerId AND contactPhoneNumber = :phone")
    suspend fun getContactByPhone(ownerId: String, phone: String): ContactEntity?

    @Query("SELECT * FROM contacts")
    fun getAllContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts")
    fun getAllContactsList(): List<ContactEntity>

    // Bildirim sayıları için (PeersFragment kullanıyor)
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

    @Query("SELECT * FROM contacts WHERE contactServerId = :id OR contactPhoneNumber = :id LIMIT 1")
    fun getContactByIdFlow(id: String): Flow<ContactEntity?>

    @Query("SELECT * FROM contacts WHERE contactServerId = :id OR contactPhoneNumber = :id LIMIT 1")
    suspend fun getContactById(id: String): ContactEntity?

    @Query("DELETE FROM contacts WHERE ownerId = :ownerId AND contactPhoneNumber = :phoneNumber")
    suspend fun deleteContact(ownerId: String, phoneNumber: String)

    @Query("UPDATE contacts SET ibePublicKey = :publicKey WHERE ownerId = :ownerId AND contactPhoneNumber = :phone")
    suspend fun updatePublicKey(ownerId: String, phone: String, publicKey: String)

    @Query("UPDATE contacts SET contactLatitude = :lat, contactLongitude = :lng WHERE contactServerId = :id OR contactPhoneNumber = :id")
    suspend fun updateContactLocation(id: String, lat: Double, lng: Double)
}