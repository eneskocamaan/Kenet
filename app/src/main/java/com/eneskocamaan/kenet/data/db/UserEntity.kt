package com.eneskocamaan.kenet.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_profile")
data class UserEntity(
    // Benim sabit ID'm (Backend'den türetilen 8 karakterli ID)
    @PrimaryKey
    val userId: String,

    // Telefon numarası (Afet durumunda iletişim için kritik)
    val phoneNumber: String,

    // Kullanıcının adı (Diğer kullanıcıların rehberinde görünür)
    val displayName: String,

    // Güvenlik Anahtarları (Backend'den gelen IBE Private Key)
    val privateKey: String,

    // Sistemin genel şifreleme parametreleri (IBE Public Parameters)
    val publicParams: String,

    // Opsiyonel afet bilgisi
    val bloodType: String? = null


)