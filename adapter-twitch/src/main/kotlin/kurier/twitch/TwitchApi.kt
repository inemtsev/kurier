package kurier.twitch

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kurier.KurierException
import kotlin.time.Duration

/**
 * Thin Ktor wrapper over Twitch's official API: Helix REST (validate / resolve / subscribe / send)
 * plus the EventSub WebSocket. Owns its client and engine — [close] shuts both down. The engine is
 * injectable so REST methods can be tested with a `MockEngine`.
 *
 * When [clientSecret] and a [refreshToken] are supplied, an expired access token is refreshed on the
 * fly: any Helix/validate call that returns `401` triggers a refresh-grant exchange and one retry, so
 * a long-lived connection survives the ~4h user-token lifetime without operator intervention.
 */
internal class TwitchApi(
    private val clientId: String,
    accessToken: String,
    private val clientSecret: String? = null,
    refreshToken: String? = null,
    engine: HttpClientEngine = CIO.create(),
) {
    private var accessToken = accessToken
    private var refreshToken = refreshToken

    private val client = HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(WebSockets)
    }

    /** Validates the token and returns the authenticated bot account. Twitch's validate uses `OAuth`, not Bearer. */
    suspend fun validate(): ValidateResponse {
        val response = authed {
            client.get(VALIDATE_URL) { header(HttpHeaders.Authorization, "OAuth $accessToken") }
        }
        if (!response.status.isSuccess()) {
            throw TwitchApiException("validate", response.status.value, response.bodyAsText())
        }
        return response.body()
    }

    /** Resolves a channel login to its broadcaster account, or null if no such user exists. */
    suspend fun resolveBroadcaster(login: String): TwitchUser? {
        val response = authed {
            client.get("$HELIX/users") {
                helixHeaders()
                parameter("login", login)
            }
        }
        if (!response.status.isSuccess()) {
            throw TwitchApiException("resolveBroadcaster", response.status.value, response.bodyAsText())
        }
        return response.body<UsersResponse>().data.firstOrNull()
    }

    /** Subscribes this WebSocket [sessionId] to the channel's chat messages. Throws on a non-2xx response. */
    suspend fun createSubscription(broadcasterId: String, userId: String, sessionId: String) {
        val response = authed {
            client.post("$HELIX/eventsub/subscriptions") {
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
        val response = authed {
            client.post("$HELIX/chat/messages") {
                helixHeaders()
                contentType(ContentType.Application.Json)
                setBody(SendMessageRequest(broadcasterId = broadcasterId, senderId = senderId, message = message))
            }
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

    /**
     * Opens the EventSub WebSocket and delivers each text frame to [onText], which returns false to stop
     * receiving so the caller can reconnect. Enforces a read deadline from [keepaliveTimeout]: if no frame
     * — keepalive or notification — arrives within it, the socket is treated as dead and this returns,
     * rather than blocking forever on a silently dropped connection. Returns when the socket closes,
     * stalls past the deadline, or [onText] asks to stop.
     */
    suspend fun openEventSub(keepaliveTimeout: () -> Duration, onText: suspend (String) -> Boolean) {
        client.webSocket(EVENTSUB_URL) {
            while (true) {
                // null is a timed-out read (zombie socket) or a closed channel; a Text frame that onText
                // rejects (reconnect/revocation) also ends the session. Everything else keeps receiving.
                val frame = withTimeoutOrNull(keepaliveTimeout()) { incoming.receiveCatching().getOrNull() }
                val keepReceiving = frame != null && (frame !is Frame.Text || onText(frame.readText()))
                if (!keepReceiving) break
            }
        }
    }

    private fun HttpRequestBuilder.helixHeaders() {
        header(HttpHeaders.Authorization, "Bearer $accessToken")
        header("Client-Id", clientId)
    }

    /**
     * Runs [request]; if it comes back `401` and a refresh succeeds, re-runs it once with the new token.
     * [request] reads [accessToken] when invoked, so the retry naturally uses the refreshed value.
     */
    private suspend fun authed(request: suspend () -> HttpResponse): HttpResponse {
        val response = request()
        return if (response.status == HttpStatusCode.Unauthorized && refresh()) request() else response
    }

    /** Exchanges the refresh token for a new access token (and possibly a rotated refresh token). */
    private suspend fun refresh(): Boolean {
        val secret = clientSecret
        val token = refreshToken
        if (secret == null || token == null) return false
        val response = client.submitForm(
            url = TOKEN_URL,
            formParameters = parameters {
                append("grant_type", "refresh_token")
                append("refresh_token", token)
                append("client_id", clientId)
                append("client_secret", secret)
            },
        )
        return if (response.status.isSuccess()) {
            val refreshed = response.body<TokenResponse>()
            accessToken = refreshed.accessToken
            refreshToken = refreshed.refreshToken ?: refreshToken
            true
        } else {
            false
        }
    }

    fun close() {
        client.close()
    }

    companion object {
        const val CHAT_MESSAGE_TYPE: String = "channel.chat.message"
        private const val HELIX = "https://api.twitch.tv/helix"
        private const val EVENTSUB_URL = "wss://eventsub.wss.twitch.tv/ws"
        private const val VALIDATE_URL = "https://id.twitch.tv/oauth2/validate"
        private const val TOKEN_URL = "https://id.twitch.tv/oauth2/token"
    }
}

/**
 * Raised when a Helix call responds non-2xx (e.g. bad token, missing scope) or accepts but drops
 * the message (AutoMod). Retryable on `429`/5xx per the [KurierException] contract.
 */
internal class TwitchApiException(
    method: String,
    val status: Int,
    body: String,
) : KurierException(
    "Twitch API $method failed ($status): $body",
    retryable = status == TOO_MANY_REQUESTS || status >= SERVER_ERROR,
) {
    private companion object {
        const val TOO_MANY_REQUESTS = 429
        const val SERVER_ERROR = 500
    }
}
