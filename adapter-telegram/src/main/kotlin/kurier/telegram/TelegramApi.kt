package kurier.telegram

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

/**
 * Thin Ktor wrapper over the Telegram Bot API. The [request] helper unwraps the
 * shared `{ ok, result }` envelope; typed methods sit on top of it.
 *
 * The HTTP *engine* is injectable so tests can drive a `MockEngine` while the real
 * plugin/serialization config below stays under test. Owns its client and engine —
 * [close] shuts both down.
 */
internal class TelegramApi(
    token: String,
    private val engine: HttpClientEngine = defaultEngine(),
) {
    private val baseUrl = "https://api.telegram.org/bot$token"

    private val client: HttpClient = HttpClient(engine) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout)
    }

    /**
     * Long-polls for updates. [timeoutSeconds] is held open server-side, so this call
     * blocks up to that long when no updates are pending; the HTTP request timeout is
     * capped just above it so a wedged connection fails fast instead of hanging.
     */
    suspend fun getUpdates(offset: Long, timeoutSeconds: Int): List<Update> =
        request("getUpdates") {
            parameter("offset", offset)
            parameter("timeout", timeoutSeconds)
            timeout {
                // Cap just above Telegram's server-side hold: a longer request means a
                // wedged connection, so fail it (→ reconnect) rather than hang forever.
                requestTimeoutMillis = (timeoutSeconds + TIMEOUT_SLACK_SECONDS).seconds.inWholeMilliseconds
            }
        }

    /** Returns the bot's own account — validates the token and resolves the bot identity. */
    suspend fun getMe(): User = request("getMe")

    /** Sends a message to [chatId] with optional formatting [entities], returning the created [Message]. */
    suspend fun sendMessage(chatId: Long, text: String, entities: List<MessageEntity>? = null): Message =
        post("sendMessage", SendMessageRequest(chatId = chatId, text = text, entities = entities))

    /**
     * Edits a message's text in place — the per-token step of streaming-edit replies. Chat-addressed
     * only: it always returns the edited [Message]. Inline messages (posted via inline mode) are
     * addressed by `inline_message_id` and make this method return `true` instead; supporting them
     * would be a separate method and inbound path, since inline messages have no `ChannelId`.
     */
    suspend fun editMessageText(chatId: Long, messageId: Long, text: String, entities: List<MessageEntity>? = null): Message =
        post(
            "editMessageText",
            EditMessageTextRequest(chatId = chatId, messageId = messageId, text = text, entities = entities),
        )

    suspend fun deleteMessage(chatId: Long, messageId: Long) {
        post<DeleteMessageRequest, Boolean>("deleteMessage", DeleteMessageRequest(chatId = chatId, messageId = messageId))
    }

    /** Shows a chat action (e.g. `"typing"`); it clears on its own after a few seconds or the next message. */
    suspend fun sendChatAction(chatId: Long, action: String) {
        post<SendChatActionRequest, Boolean>("sendChatAction", SendChatActionRequest(chatId = chatId, action = action))
    }

    suspend fun setMessageReaction(chatId: Long, messageId: Long, emoji: String) {
        post<SetMessageReactionRequest, Boolean>(
            "setMessageReaction",
            SetMessageReactionRequest(
                chatId = chatId,
                messageId = messageId,
                reaction = listOf(ReactionTypeEmoji(type = "emoji", emoji = emoji)),
            ),
        )
    }

    /** POSTs [body] as JSON and unwraps the `{ ok, result }` envelope into [Res]. */
    private suspend inline fun <reified Req, reified Res> post(name: String, body: Req): Res =
        request(name) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend inline fun <reified T> request(
        name: String,
        block: HttpRequestBuilder.() -> Unit = {},
    ): T {
        val envelope: Response<T> = client.request("$baseUrl/$name", block).body()
        return envelope.result
            ?: throw TelegramApiException(name, envelope.errorCode, envelope.description)
    }

    fun close() {
        client.close()
        engine.close()
    }

    companion object {
        private const val REQUEST_TIMEOUT_DISABLED = 0L
        private const val TIMEOUT_SLACK_SECONDS = 5

        // The HttpTimeout plugin owns the per-request cap (see getUpdates); disable CIO's
        // built-in 15s timeout so it can't pre-empt a healthy long-poll first.
        fun defaultEngine(): HttpClientEngine = CIO.create { requestTimeout = REQUEST_TIMEOUT_DISABLED }
    }
}

/**
 * Raised when the Bot API responds with `ok: false`. The poll loop backs off and
 * retries transient failures, but gives up on [isFatal] ones — see [isFatal].
 */
internal class TelegramApiException(
    method: String,
    val errorCode: Int?,
    description: String?,
) : RuntimeException("Telegram API $method failed (${errorCode ?: "?"}): ${description ?: "no description"}") {

    /**
     * True for a permanently rejected token (`401`) — retrying never succeeds, so the
     * poll loop gives up. Per-recipient errors like `403` (user blocked the bot) belong
     * to the send path, not here: they kill one chat, not the whole connection.
     */
    val isFatal: Boolean get() = errorCode != null && errorCode in FATAL_CODES

    private companion object {
        const val UNAUTHORIZED = 401
        val FATAL_CODES = setOf(UNAUTHORIZED)
    }
}
