package com.eneskocamaan.kenet.data.api

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

// ===========================
//      REQUEST (İSTEK) MODELLERİ
// ===========================

data class RequestOtpRequest(
    @SerializedName("phone_number") val phoneNumber: String
)

data class VerifyOtpRequest(
    @SerializedName("phone_number") val phoneNumber: String,
    @SerializedName("code") val code: String
)

data class CompleteProfileRequest(
    @SerializedName("phone_number") val phoneNumber: String,
    @SerializedName("display_name") val displayName: String,
    @SerializedName("blood_type") val bloodType: String? = null,
    @SerializedName("contacts") val contacts: List<ContactModel> = emptyList()
)

data class UpdateLocationRequest(
    @SerializedName("user_id") val userId: String,
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double
)

data class SyncContactsRequest(
    @SerializedName("user_id") val userId: String
)

data class CheckContactsRequest(
    @SerializedName("phone_numbers") val phoneNumbers: List<String>
)

data class DeleteContactRequest(
    @SerializedName("owner_phone") val ownerPhone: String,
    @SerializedName("contact_phone") val contactPhone: String
)

data class GatewaySmsRequest(
    @SerializedName("packet_uid") val packetUid: String,
    @SerializedName("sender_id") val senderId: String,
    @SerializedName("sender_phone") val senderPhone: String,
    @SerializedName("target_phone") val targetPhone: String,
    @SerializedName("encrypted_payload") val encryptedPayload: String,
    @SerializedName("nonce") val nonce: String,
    @SerializedName("ephemeral_key") val ephemeralKey: String,
    @SerializedName("integrity_tag") val integrityTag: String
)

// ===========================
//      RESPONSE (YANIT) MODELLERİ
// ===========================

data class StatusResponse(
    @SerializedName("message") val message: String,
    @SerializedName("user_id") val userId: String? = null
)

// [GÜNCELLENDİ] Parcelable eklendi! Hatayı bu çözecek.
@Parcelize
data class VerifyOtpResponse(
    @SerializedName("is_new_user") val isNewUser: Boolean? = false,
    @SerializedName("user_id") val userId: String? = "",
    @SerializedName("phone_number") val phoneNumber: String? = "",
    @SerializedName("display_name") val displayName: String? = null,
    @SerializedName("blood_type") val bloodType: String? = null,
    @SerializedName("ibe_private_key") val ibePrivateKey: String? = null,
    @SerializedName("public_params") val publicParams: String? = null
) : Parcelable

data class CheckContactsResponse(
    @SerializedName("registered_users") val registeredUsers: List<RegisteredUserItem>
)

data class RegisteredUserItem(
    @SerializedName("user_id") val userId: String,
    @SerializedName("phone_number") val phoneNumber: String,
    @SerializedName("display_name") val displayName: String?,
    @SerializedName("blood_type") val bloodType: String?,
    @SerializedName("public_key") val publicKey: String?,
    @SerializedName("latitude") val latitude: Double?,
    @SerializedName("longitude") val longitude: Double?
)

data class SyncContactsResponse(
    @SerializedName("contacts") val contacts: List<SyncContactItem>
)

data class SyncContactItem(
    @SerializedName("contact_id") val contactId: String?,
    @SerializedName("phone_number") val phoneNumber: String,
    @SerializedName("display_name") val displayName: String,
    @SerializedName("latitude") val latitude: Double?,
    @SerializedName("longitude") val longitude: Double?,
    @SerializedName("public_key") val publicKey: String?
)

// ===========================
//      ORTAK MODELLER
// ===========================

@Parcelize
data class ContactModel(
    @SerializedName("phone_number") val phoneNumber: String,
    @SerializedName("display_name") val displayName: String,
    var isSelected: Boolean = false,
    var isKenetUser: Boolean = false
) : Parcelable

@Parcelize
data class ConfirmedEarthquakeItem(
    @SerializedName("id") val id: Int,
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("title") val title: String,
    @SerializedName("magnitude") val magnitude: Double,
    @SerializedName("depth") val depth: Double,
    @SerializedName("intensity_label") val intensityLabel: String? = "BİLİNMİYOR",
    @SerializedName("radius_km") val radiusKm: Int? = 0,
    @SerializedName("occurred_at") val occurredAt: String
) : Parcelable

data class EarthquakeListResponse(
    @SerializedName("earthquakes") val earthquakes: List<ConfirmedEarthquakeItem>
)



// Sinyal İsteği
data class SeismicSignalRequest(
    @SerializedName("user_id") val userId: String,
    @SerializedName("pga") val pga: Double,
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double
)

// Backend Tespit Yanıtı
data class AppDetectedEventResponse(
    @SerializedName("id") val id: Int,
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("intensity_label") val intensityLabel: String,
    @SerializedName("max_pga") val maxPga: Double,
    @SerializedName("participating_users") val participatingUsers: Int,
    @SerializedName("created_at") val createdAt: String
)