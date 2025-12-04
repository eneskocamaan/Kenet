package com.eneskocamaan.kenet.data.model.remote.response

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

/**
 * OTP doğrulama işleminden sonra sunucudan gelen yanıtı temsil eden veri sınıfı.
 */
@Parcelize
data class VerifyOtpResponse(
    @SerializedName("is_new_user")
    val isNewUser: Boolean,

    @SerializedName("user_id")
    val userId: String,

    @SerializedName("phone_number")
    val phoneNumber: String,

    @SerializedName("display_name")
    val displayName: String?,

    @SerializedName("blood_type")
    val bloodType: String?,

    @SerializedName("ibe_private_key")
    val ibePrivateKey: String,

    @SerializedName("public_params")
    val publicParams: String
) : Parcelable
