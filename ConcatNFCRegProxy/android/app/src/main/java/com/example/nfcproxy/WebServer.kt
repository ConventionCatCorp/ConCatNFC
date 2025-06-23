package com.example.nfcproxy

import android.util.Base64
import android.util.Log
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.sse.*
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object SseEventBus {
    private val _events = MutableSharedFlow<String>(replay = 1)
    val events = _events.asSharedFlow()

    suspend fun sendEvent(event: String) {
        _events.emit(event)
    }
}

fun Application.module(nfcInterface: NFCInterface) {
    nfcInterface.setEventListener(SseEventBus::sendEvent)
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

    install(SSE)

    install(ContentNegotiation) {
        json(Json {
            prettyPrint = false
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    routing {
        sse("/events") {
            Log.d("WebServer", "SSE connected")
            SseEventBus.events.collectLatest { event ->
                send(ServerSentEvent(event))
            }
            Log.d("WebServer", "SSE Done")
        }
        get("/uuid") {
            try {
                val uuid = nfcInterface.GetUUID()
                val response = Response(uuid = uuid, success = true)
                launch {
                    SseEventBus.sendEvent("New UUID read: $uuid")
                }
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
