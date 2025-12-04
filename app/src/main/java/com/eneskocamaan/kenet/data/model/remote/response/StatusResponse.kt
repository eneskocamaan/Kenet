package com.eneskocamaan.kenet.data.model.remote.response

import com.google.gson.annotations.SerializedName

data class StatusResponse(
    @SerializedName("message")
    val message: String,

    // BU ALANI EKLEMELİSİNİZ:
    @SerializedName("user_id")
    val userId: String? = null
)