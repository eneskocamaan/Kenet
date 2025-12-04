package com.eneskocamaan.kenet.data.model.remote.request

import com.eneskocamaan.kenet.data.api.ContactModel // ContactModel'in olduğu yeri import etmeyi unutma
import com.google.gson.annotations.SerializedName

/**
 * Yeni kullanıcının profilini tamamlamak için sunucuya gönderilen veri sınıfı.
 */
data class CompleteProfileRequest(
    @SerializedName("phone_number")
    val phoneNumber: String,

    @SerializedName("display_name")
    val displayName: String,

    @SerializedName("blood_type")
    val bloodType: String?,

    @SerializedName("contacts")
    val contacts: List<ContactModel> = emptyList()
)