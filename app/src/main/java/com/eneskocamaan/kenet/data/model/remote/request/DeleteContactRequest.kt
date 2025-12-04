package com.eneskocamaan.kenet.data.model.remote.request

import com.google.gson.annotations.SerializedName

data class DeleteContactRequest(
    @SerializedName("owner_phone") val ownerPhone: String,
    @SerializedName("contact_phone") val contactPhone: String
)