package com.example.nfcproxy

import android.util.Base64
import android.util.Log
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.request.receive
import io.ktor.routing.*
import io.ktor.response.*
import io.ktor.serialization.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable


fun Application.module(nfcInterface: NFCInterface) {
    @Serializable
    data class CardDefinitionRequest(
        val attendeeId: UInt? = null,
        val conventionId: UInt? = null,
        val issuance: UInt? = null,
        val timestamp: ULong? = null,
        val expiration: ULong? = null,
        val signature: String? = null,
        val password: UInt? = null,
        val uuid: String? = null
    )

    @Serializable
    data class Response(
        val uuid: String? = null,
        val error: String? = null,
        val success: Boolean,
        val card: CardDefinitionRequest? = null
    )

    @Serializable
    data class Tag(
        val id: Byte,
        val data: ByteArray
    )

    @Serializable
    data class CardReadSetPasswordRequest(
        val password: UInt? = null,
        val uuid: String? = null
    )

    install(ContentNegotiation) {
        json(Json {
            prettyPrint = false
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    routing {
        get("/uuid") {
            try {
                val uuid = nfcInterface.GetUUID()
                val response = Response(uuid = uuid, success = true)
                call.respond(response)
            } catch (e: NFCInterfaceException) {
                call.respond(HttpStatusCode.InternalServerError, "Error: ${e.message}")

            }
        }
        put("/read") {
            try {
                val payload = call.receive<CardReadSetPasswordRequest>()
                if (payload.uuid == null) {
                    call.respond(HttpStatusCode.BadRequest, "Missing uuid")
                    return@put
                }
                Log.d("WebServer", "UUID: ${payload.uuid}")
                val lUid = nfcInterface.GetUUID()
                if (payload.uuid != lUid) {
                    call.respond(HttpStatusCode.BadRequest,
                        "Mismatched card UUID. Did you swapped the card between operations? Current UUID=$lUid"
                    )
                    return@put
                }
                if (payload.password != null) {
                    Log.d("WebServer", "Attempting card unlock")
                    nfcInterface.NTAG21xAuth(payload.password)
                }
                Log.d("WebServer", "Reading tags")
                val tags = nfcInterface.readTags()
                val response = Response(card = CardDefinitionRequest(
                    attendeeId = tags.getAttendeeAndConvention().getOrElse { throw it }.first,
                    conventionId = tags.getAttendeeAndConvention().getOrElse { throw it }.second,
                    issuance = tags.getIssuance().getOrElse { throw it }.toUInt(),
                    timestamp = tags.getTimestamp().getOrElse { throw it },
                    expiration = tags.getExpiration().getOrNull(),
                    signature = Base64.encodeToString(tags.getSignature().getOrElse { throw it }, Base64.NO_WRAP)
                ), success = true)
                call.respond(response)
            } catch (e: NFCInterfaceException) {
                call.respond(HttpStatusCode.InternalServerError, "NFC Error: ${e.message}")
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Error: ${e.message}")
            }
        }
    }
}
