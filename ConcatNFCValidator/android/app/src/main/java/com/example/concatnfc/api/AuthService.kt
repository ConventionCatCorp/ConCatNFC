package com.example.concatnfc.api

import com.example.concatnfc.api.model.LoginRequest
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

data class NfcPasswordResponse(
    val nfcPassword: String
)

class JWK(
    val kty: String,
    val x: String,
    val y: String,
    val crv: String,
) {
    fun toJSONString(): String {
        return """{"kty":"$kty","x":"$x","y":"$y","crv":"$crv"}"""
    }
}

interface AuthService {
    @GET("api/health")
    @Headers("Accept: application/json")
    suspend fun getCsrfToken(): Response<Unit>

    @POST("api/login")
    @Headers("Content-Type: application/json")
    suspend fun login(
        @Header("x-csrf") csrfToken: String? = null,
        @Body loginRequest: LoginRequest
    ): Response<ResponseBody>
    
    @GET("api/badge/nfc/password")
    @Headers("Accept: application/json")
    suspend fun getNfcPassword(
        @Query("uuid") uuid: String
    ): Response<NfcPasswordResponse>

    @GET("api/nfc/key")
    @Headers("Accept: application/json")
    suspend fun getNfcKey(): Response<JWK>
}
