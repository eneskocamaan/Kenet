package com.eneskocamaan.kenet.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "contacts",
    primaryKeys = ["ownerId", "contactPhoneNumber"], // Bir kişi aynı numarayı iki kere ekleyemesin
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["userId"],
            childColumns = ["ownerId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ContactEntity(
    val ownerId: String,          // Şu anki kullanıcının ID'si
    val contactPhoneNumber: String, // Rehberdeki kişinin numarası
    val contactName: String,      // Rehberdeki adı
    val contactServerId: String? = null // Sunucudan dönecek olan contact_id (eşleşirse)
)