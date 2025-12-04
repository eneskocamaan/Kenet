package com.eneskocamaan.kenet.data.model.remote.request

import com.google.gson.annotations.SerializedName

/**
 * Sunucuya OTP kodunu doğrulamak için gönderilen veri sınıfı.
 */
data class VerifyOtpRequest(
    @SerializedName("phone_number")
    val phoneNumber: String,

    @SerializedName("code")
    val code: String
)
