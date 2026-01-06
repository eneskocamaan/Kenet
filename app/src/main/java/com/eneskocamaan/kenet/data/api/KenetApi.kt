package com.eneskocamaan.kenet.data.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface KenetApi {

    @POST("request_otp")
    suspend fun requestOtp(@Body request: RequestOtpRequest): Response<StatusResponse>

    @POST("verify_otp")
    suspend fun verifyOtp(@Body request: VerifyOtpRequest): Response<VerifyOtpResponse>

    // --- DÜZELTME BURADA YAPILDI ---
    // Backend StatusResponse dönüyor, VerifyOtpResponse değil!
    @POST("complete_profile")
    suspend fun completeProfile(@Body request: CompleteProfileRequest): Response<StatusResponse>

    @POST("update_location")
    suspend fun updateLocation(@Body request: UpdateLocationRequest): Response<StatusResponse>

    @POST("check_contacts")
    suspend fun checkContacts(@Body request: CheckContactsRequest): Response<CheckContactsResponse>

    @POST("sync_contacts")
    suspend fun syncContacts(@Body request: SyncContactsRequest): Response<SyncContactsResponse>

    @POST("delete_contact")
    suspend fun deleteContact(@Body request: DeleteContactRequest): Response<StatusResponse>

    // Endpoint ismi app.py ile aynı (/send_gateway_sms) - DOĞRU
    @POST("send_gateway_sms")
    suspend fun sendGatewaySms(@Body request: GatewaySmsRequest): Response<StatusResponse>

    // 1. Sinyal Gönder (Telefon Sallanınca)
    @POST("signal")
    suspend fun sendSeismicSignal(@Body request: SeismicSignalRequest): Response<StatusResponse>

    // 2. Kenet Algılamalarını Çek (1. Sekme Polling)
    @GET("app_detected_events")
    suspend fun getAppDetectedEvents(): Response<List<AppDetectedEventResponse>>

    @GET("confirmed_earthquakes")
    suspend fun getConfirmedEarthquakes(): Response<EarthquakeListResponse>




}