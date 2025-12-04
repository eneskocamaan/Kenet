package com.eneskocamaan.kenet.data.api

import com.eneskocamaan.kenet.data.model.remote.request.CompleteProfileRequest
import com.eneskocamaan.kenet.data.model.remote.request.DeleteContactRequest
import com.eneskocamaan.kenet.data.model.remote.request.RequestOtpRequest
import com.eneskocamaan.kenet.data.model.remote.request.VerifyOtpRequest
import com.eneskocamaan.kenet.data.model.remote.response.StatusResponse
import com.eneskocamaan.kenet.data.model.remote.response.VerifyOtpResponse
import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * KENET backend API'si için Retrofit arayüzü.
 */
interface KenetApi {

    /**
     * Sunucudan bir defalık şifre (OTP) talep eder.
     * @param request Telefon numarasını içeren istek gövdesi.
     * @return Basit bir durum yanıtı.
     */
    @POST("request_otp")
    suspend fun requestOtp(@Body request: RequestOtpRequest): Response<StatusResponse>

    /**
     * Sunucuya OTP'yi doğrulamak için gönderir.
     * @param request Telefon numarası ve kodu içeren istek gövdesi.
     * @return Kullanıcının yeni olup olmadığını ve profil bilgilerini içeren yanıt.
     */
    @POST("verify_otp")
    suspend fun verifyOtp(@Body request: VerifyOtpRequest): Response<VerifyOtpResponse>

    /**
     * Yeni kullanıcının profil bilgilerini (isim, kan grubu) tamamlar.
     * @param request Profil bilgilerini içeren istek gövdesi.
     * @return Basit bir durum yanıtı.
     */
    @POST("complete_profile")
    suspend fun completeProfile(@Body request: CompleteProfileRequest): Response<StatusResponse>

    @POST("check_contacts")
    suspend fun checkContacts(@Body request: CheckContactsRequest): Response<CheckContactsResponse>

    @POST("sync_contacts")
    suspend fun syncContacts(@Body request: SyncContactsRequest): Response<SyncContactsResponse>

    @POST("delete_contact")
    suspend fun deleteContact(@Body request: DeleteContactRequest): Response<StatusResponse>

}

data class CheckContactsRequest(
    @SerializedName("phone_numbers") val phoneNumbers: List<String>
)

data class CheckContactsResponse(
    @SerializedName("registered_numbers") val registeredNumbers: List<String>
)

