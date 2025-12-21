package com.eneskocamaan.kenet.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_profile")
data class UserEntity(
    @PrimaryKey
    val userId: String,         // Benim Kenet ID'm

    val phoneNumber: String,    // Telefon Numaram
    val displayName: String,    // Görünen Adım

    // EKLENEN ALANLAR:
    val ibePrivateKey: String? = null, // Şifre çözmek için gerekli
    val publicParams: String? = null,   // Public Key

    val bloodType: String? = null,

    // Kendi anlık konumum (GPSR paketlerine eklemek için)
    val latitude: Double? = null,
    val longitude: Double? = null
)