package com.eneskocamaan.kenet.data.db

import androidx.room.Entity
import androidx.room.ForeignKey

@Entity(
    tableName = "contacts",
    primaryKeys = ["ownerId", "contactPhoneNumber"],
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
    val ownerId: String,            // Bu rehber kime ait? (Benim ID'm)
    val contactPhoneNumber: String, // Kişinin Numarası
    val contactName: String,        // Kişinin Adı

    val contactServerId: String? = null, // Kenet ID'si (Varsa)

    // Kişinin son bilinen konumu (Mesaj gönderirken hedef olarak kullanılır)
    val contactLatitude: Double? = null,
    val contactLongitude: Double? = null
)