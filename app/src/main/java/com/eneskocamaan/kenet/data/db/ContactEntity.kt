package com.eneskocamaan.kenet.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

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
    ],
    indices = [Index(value = ["contactServerId"])]
)
data class ContactEntity(
    val ownerId: String,
    val contactPhoneNumber: String,
    val contactName: String,

    val contactServerId: String? = null,
    val contactDisplayName: String? = null,
    val ibePublicKey: String? = null,
    val bloodType: String? = null, // YENİ EKLENEN

    val contactLatitude: Double? = null,
    val contactLongitude: Double? = null,
    val lastSeenTimestamp: Long? = null
)