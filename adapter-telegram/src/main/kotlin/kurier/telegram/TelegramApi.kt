package kurier.telegram

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
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

    private suspend inline fun <reified T> request(
        method: String,
        block: HttpRequestBuilder.() -> Unit = {},
    ): T {
        val envelope: Response<T> = client.get("$baseUrl/$method", block).body()
        return envelope.result
            ?: throw TelegramApiException(method, envelope.errorCode, envelope.description)
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
