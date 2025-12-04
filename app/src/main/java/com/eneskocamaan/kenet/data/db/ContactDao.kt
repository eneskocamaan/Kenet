package com.eneskocamaan.kenet.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ContactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContacts(contacts: List<ContactEntity>)

    @Query("SELECT * FROM contacts WHERE ownerId = :ownerId")
    suspend fun getContacts(ownerId: String): List<ContactEntity>

    @Query("DELETE FROM contacts WHERE ownerId = :ownerId AND contactPhoneNumber = :phoneNumber")
    suspend fun deleteContact(ownerId: String, phoneNumber: String)
}