package kurier.twitch

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json

/**
 * Thin Ktor wrapper over Twitch's official API: Helix REST (validate / resolve / subscribe / send)
 * plus the EventSub WebSocket. Owns its client and engine — [close] shuts both down. The engine is
 * injectable so REST methods can be tested with a `MockEngine`.
 */
internal class TwitchApi(
    private val clientId: String,
    private val accessToken: String,
    engine: HttpClientEngine = CIO.create(),
) {
    private val client = HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(WebSockets)
    }

    /** Validates the token and returns the authenticated bot account. Twitch's validate uses `OAuth`, not Bearer. */
    suspend fun validate(): ValidateResponse =
        client.get("https://id.twitch.tv/oauth2/validate") {
            header(HttpHeaders.Authorization, "OAuth $accessToken")
        }.body()

    /** Resolves a channel login to its broadcaster account, or null if no such user exists. */
    suspend fun resolveBroadcaster(login: String): TwitchUser? =
        client.get("$HELIX/users") {
            helixHeaders()
            parameter("login", login)
        }.body<UsersResponse>().data.firstOrNull()

    /** Subscribes this WebSocket [sessionId] to the channel's chat messages. Throws on a non-2xx response. */
    suspend fun createSubscription(broadcasterId: String, userId: String, sessionId: String) {
        val response = client.post("$HELIX/eventsub/subscriptions") {
            helixHeaders()
            contentType(ContentType.Application.Json)
            setBody(
                SubscriptionRequest(
                    type = CHAT_MESSAGE_TYPE,
                    version = "1",
                    condition = ChatCondition(broadcasterUserId = broadcasterId, userId = userId),
                    transport = Transport(method = "websocket", sessionId = sessionId),
                ),
            )
        }
        if (!response.status.isSuccess()) {
            throw TwitchApiException("createSubscription", response.status.value, response.bodyAsText())
        }
    }

    /**
     * Posts [message] to the broadcaster's chat as [senderId] and returns the new message id. Twitch
     * acks a rejected message with HTTP 200 + `is_sent=false` (e.g. dropped by AutoMod), so a non-sent
     * result is surfaced as a failure carrying the drop reason — not silently swallowed.
     */
    suspend fun sendMessage(broadcasterId: String, senderId: String, message: String): String {
        val response = client.post("$HELIX/chat/messages") {
            helixHeaders()
            contentType(ContentType.Application.Json)
            setBody(SendMessageRequest(broadcasterId = broadcasterId, senderId = senderId, message = message))
        }
        if (!response.status.isSuccess()) {
            throw TwitchApiException("sendMessage", response.status.value, response.bodyAsText())
        }
        val result = response.body<SendMessageResponse>().data.firstOrNull()
        if (result?.isSent != true) { // null data or a dropped message (e.g. AutoMod) both count as not sent
            throw TwitchApiException("sendMessage", response.status.value, result?.dropReason?.message ?: "message not sent")
        }
        return result.messageId
    }

    /** Opens the EventSub WebSocket, delivering each text frame to [onText]; returns when the socket closes. */
    suspend fun openEventSub(onText: suspend (String) -> Unit) {
        client.webSocket(EVENTSUB_URL) {
            for (frame in incoming) {
                if (frame is Frame.Text) onText(frame.readText())
            }
        }
    }

    private fun HttpRequestBuilder.helixHeaders() {
        header(HttpHeaders.Authorization, "Bearer $accessToken")
        header("Client-Id", clientId)
    }

    fun close() {
        client.close()
    }

    companion object {
        const val CHAT_MESSAGE_TYPE: String = "channel.chat.message"
        private const val HELIX = "https://api.twitch.tv/helix"
        private const val EVENTSUB_URL = "wss://eventsub.wss.twitch.tv/ws"
    }
}

/** Raised when a Helix call responds non-2xx (e.g. bad token, missing scope). */
internal class TwitchApiException(
    method: String,
    val status: Int,
    body: String,
) : RuntimeException("Twitch API $method failed ($status): $body")
