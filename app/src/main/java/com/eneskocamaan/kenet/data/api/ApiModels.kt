package com.eneskocamaan.kenet.data.api

import android.os.Parcelable
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

// Profil Tamamlama İsteği (Kişi listesi de ekleniyor)
data class CompleteProfileRequest(
    val phone_number: String,
    val display_name: String,
    val blood_type: String? = null,
    val contacts: List<ContactModel> = emptyList()
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

/**
 * Hem Rehberden kişi seçerken UI model olarak,
 * Hem Fragmentlar arası veri taşırken (Parcelable),
 * Hem de API'ye gönderirken JSON modeli olarak kullanılır.
 */
@Parcelize
data class ContactModel(
    // Değişken isimlerini API formatına (snake_case) uygun yaptım
    val phone_number: String,
    val display_name: String,
    var isSelected: Boolean = false,
    var isKenetUser: Boolean = false
) : Parcelable


// --- SYNC MODELLERİ ---

data class SyncContactsRequest(
    val user_id: String
)

data class SyncContactDto(
    val contact_id: String?,
    val phone_number: String,
    val display_name: String
)

data class SyncContactsResponse(
    val contacts: List<SyncContactDto>
)