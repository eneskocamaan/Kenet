package com.eneskocamaan.kenet.data.model.remote.request

import com.google.gson.annotations.SerializedName

/**
 * Sunucudan OTP talep etmek için kullanılan veri sınıfı.
 */
data class RequestOtpRequest(
    @SerializedName("phone_number")
    val phoneNumber: String
)
