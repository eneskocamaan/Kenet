package com.eneskocamaan.kenet.data.api

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

// --- API İSTEK MODELLERİ (REQUESTS) ---

data class RegisterRequest(
    val phone_number: String,
    val display_name: String,
    val blood_type: String? = null
)

data class VerificationRequest(
    val phone_number: String,
    val code: String
)

// Profil Tamamlama İsteği
data class CompleteProfileRequest(
    val phone_number: String,
    val display_name: String,
    val blood_type: String? = null,
    val contacts: List<ContactModel> = emptyList()
)

// [YENİ] Konum Güncelleme İsteği
data class UpdateLocationRequest(
    val user_id: String,
    val latitude: Double,
    val longitude: Double
)

// --- API YANIT MODELLERİ (RESPONSES) ---

data class UserProfileResponse(
    val user_id: String,
    val display_name: String,
    val ibe_private_key: String,
    val public_params: String
)

data class StatusResponse(
    val message: String
)

// --- ORTAK MODELLER ---

@Parcelize
data class ContactModel(
    val phone_number: String,
    val display_name: String,
    var isSelected: Boolean = false,
    var isKenetUser: Boolean = false
) : Parcelable


// --- SYNC MODELLERİ ---

data class SyncContactsRequest(
    val user_id: String
)

// [GÜNCELLENDİ] Sunucudan gelen kişi bilgisi (Konum eklendi)
data class SyncContactDto(
    val contact_id: String?,
    val phone_number: String,
    val display_name: String,
    // Sunucudan gelen anlık konum bilgileri (Backend models.py ile uyumlu)
    val latitude: Double? = null,
    val longitude: Double? = null
)

data class SyncContactsResponse(
    val contacts: List<SyncContactDto>
)

// KenetApi dosyasının altındaki modelleri de buraya taşıyabilirsin veya orada tutabilirsin
// Düzenli olması için CheckContacts modellerini de buraya ekliyorum:

data class CheckContactsRequest(
    @SerializedName("phone_numbers") val phoneNumbers: List<String>
)

data class CheckContactsResponse(
    @SerializedName("registered_numbers") val registeredNumbers: List<String>
)