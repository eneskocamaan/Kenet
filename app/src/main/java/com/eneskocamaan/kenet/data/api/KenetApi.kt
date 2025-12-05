package com.eneskocamaan.kenet.data.api

import com.eneskocamaan.kenet.data.model.remote.request.CompleteProfileRequest
import com.eneskocamaan.kenet.data.model.remote.request.DeleteContactRequest
import com.eneskocamaan.kenet.data.model.remote.request.RequestOtpRequest
import com.eneskocamaan.kenet.data.model.remote.request.VerifyOtpRequest
import com.eneskocamaan.kenet.data.model.remote.response.VerifyOtpResponse
// Not: Yukarıdaki importlar senin mevcut paket yapına göre kalabilir,
// ancak yeni eklediğimiz modeller (UpdateLocationRequest vb.)
// aynı pakette (com.eneskocamaan.kenet.data.api) olduğu için import gerekmeyebilir.

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * KENET backend API'si için Retrofit arayüzü.
 */
interface KenetApi {

    @POST("request_otp")
    suspend fun requestOtp(@Body request: RequestOtpRequest): Response<StatusResponse>

    @POST("verify_otp")
    suspend fun verifyOtp(@Body request: VerifyOtpRequest): Response<VerifyOtpResponse>

    @POST("complete_profile")
    suspend fun completeProfile(@Body request: CompleteProfileRequest): Response<VerifyOtpResponse>

    // [YENİ] Konum Güncelleme Endpoint'i
    // Splash ekranda ve periyodik olarak çağrılır.
    @POST("update_location")
    suspend fun updateLocation(@Body request: UpdateLocationRequest): Response<StatusResponse>

    @POST("check_contacts")
    suspend fun checkContacts(@Body request: CheckContactsRequest): Response<CheckContactsResponse>

    @POST("sync_contacts")
    suspend fun syncContacts(@Body request: SyncContactsRequest): Response<SyncContactsResponse>

    @POST("delete_contact")
    suspend fun deleteContact(@Body request: DeleteContactRequest): Response<StatusResponse>
}