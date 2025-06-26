package com.example.nfcproxy

import android.util.Base64
import android.util.Log

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.options
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.sse.*
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
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
    data class CardReadSetPasswordRequest(
        val password: UInt? = null,
        val uuid: String? = null
    )

    // Manually handle CORS for all requests since the plugin is not working reliably.
    intercept(ApplicationCallPipeline.Call) {
        call.response.headers.append("Access-Control-Allow-Origin", "*")
        call.response.headers.append("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept, Authorization")
        call.response.headers.append("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        proceed()
    }

    install(SSE)
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = false
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    routing {
        // Manually handle OPTIONS requests to work around plugin issue
        options("{path...}") {
            call.respond(HttpStatusCode.OK)
        }

        sse("/events") {
            Log.d("WebServer", "SSE connected")
            SseEventBus.events.collectLatest { event ->
                send(ServerSentEvent(event))
            }
            Log.d("WebServer", "SSE Done")
        }
        get("/uuid") {
            Log.d("WebServer", "Getting UUID")
            try {
                val uuid = nfcInterface.GetUUID()
                val response = Response(uuid = uuid, success = true)
                call.respond(response)
                Log.d("WebServer", "UUID done")
            } catch (e: NFCInterfaceException) {
                Log.d("WebServer", "UUID NFC error: ${e.message}")
                call.respond(HttpStatusCode.InternalServerError, "NFC Error: ${e.message}")
            } catch (e: Exception) {
                Log.e("WebServer", "UUID error: ${e.message}")
                Log.e("WebServer", e.stackTraceToString())
                call.respond(HttpStatusCode.InternalServerError, "Error: ${e.message}")
            }
        }

        post("/write") {
            Log.d("WebServer", "Processing write post")
            try {

                val payload = call.receive<CardDefinitionRequest>()
                if (payload.uuid == null) {
                    call.respond(HttpStatusCode.BadRequest, "Missing uuid")
                    return@post
                }
                Log.d("WebServer", "UUID: ${payload.uuid}")
                val lUid = nfcInterface.GetUUID()
                if (payload.uuid != lUid) {
                    call.respond(HttpStatusCode.BadRequest,
                        "Mismatched card UUID. Did you swapped the card between operations? Current UUID=$lUid"
                    )
                    return@post
                }
                if (payload.password != null) {
                    Log.d("WebServer", "Attempting card unlock")
                    nfcInterface.NTAG21xAuth(payload.password)
                }
                Log.d("WebServer", "Writing tags")
                val tags = TagArray()

                tags.addTag(Tag.newAttendeeId(payload.attendeeId, payload.conventionId))
                tags.addTag(Tag.newIssuance(payload.issuance))
                tags.addTag(Tag.newTimestamp(payload.timestamp))
                tags.addTag(Tag.newExpiration(payload.expiration))
                tags.addTag(Tag.newTimestamp(payload.expiration))
                tags.addTag(Tag.newSignature(payload.signature))

                nfcInterface.writeTags(tags)
                val response = Response(success = true)
                call.respond(response)
                Log.d("WebServer", "read success")
                return@post
            } catch (e: NFCInterfaceException) {
                Log.d("NFCInterfaceException", "except: ${e}")
                if (e.message!!.startsWith("Failed to read page")) {
                    call.respond(HttpStatusCode.Forbidden, Response(error = e.message, success = false))
                    return@post
                }
                call.respond(HttpStatusCode.InternalServerError, Response(error = e.message, success = false))
                return@post
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Error: ${e.message}")
                Log.e("WebServer", "Error: ${e.message}")
            }
        }
        put("/read") {
            Log.d("WebServer", "Processing read")
            try {
                val payload = call.receive<CardReadSetPasswordRequest>()
                if (payload.uuid == null) {
                    call.respond(HttpStatusCode.BadRequest, "Missing uuid")
                    return@put
                }
                Log.d("WebServer", "UUID: ${payload.uuid}")
                var tags: TagArray
                try {
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
                    tags = nfcInterface.readTags()
                    nfcInterface.resetCard()
                } catch (e: NFCInterfaceException) {
                    Log.d("NFCInterfaceException", "except: ${e}")
                    if (e.message!!.startsWith("Failed to read page")) {
                        call.respond(HttpStatusCode.Forbidden, Response(error = e.message, success = false))
                        return@put
                    }
                    call.respond(HttpStatusCode.InternalServerError, Response(error = e.message, success = false))
                    return@put
                }
                val response = Response(card = CardDefinitionRequest(
                    attendeeId = tags.getAttendeeAndConvention().getOrElse { throw it }.first,
                    conventionId = tags.getAttendeeAndConvention().getOrElse { throw it }.second,
                    issuance = tags.getIssuance().getOrElse { throw it }.toUInt(),
                    timestamp = tags.getTimestamp().getOrElse { throw it },
                    expiration = tags.getExpiration().getOrNull(),
                    signature = Base64.encodeToString(tags.getSignature().getOrElse { throw it }, Base64.NO_WRAP)
                ), success = true)
                call.respond(response)
                Log.d("WebServer", "read success")
            } catch (e: NFCInterfaceException) {
                call.respond(HttpStatusCode.InternalServerError, "NFC Error: ${e.message}")
                Log.e("WebServer", "NFC Error: ${e.message}")
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Error: ${e.message}")
                Log.e("WebServer", "Error: ${e.message}")
            }
        }
    }
}
