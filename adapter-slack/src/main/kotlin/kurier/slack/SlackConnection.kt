package kurier.slack

import com.slack.api.Slack
import com.slack.api.SlackConfig
import com.slack.api.methods.SlackApiException
import com.slack.api.methods.request.auth.AuthTestRequest
import com.slack.api.methods.response.auth.AuthTestResponse
import com.slack.api.socket_mode.SocketModeClient
import com.slack.api.socket_mode.request.EventsApiEnvelope
import com.slack.api.socket_mode.response.AckResponse
import com.slack.api.util.json.GsonFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kurier.AdapterConnection
import kurier.ChannelEvent
import kurier.ConnectionState
import kurier.IncomingMessage
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.Channel as CoroutineChannel

/**
 * One live Slack Socket Mode connection. On start it calls `auth.test` (→ the bot's own user id, for
 * the self-filter and directedness) and opens the Socket Mode client on the Java-WebSocket backend.
 * The SDK owns the session from there — its monitor reconnects dropped sockets and it honors Slack's
 * `disconnect` rotation frames itself — so, like Discord's Kord, this connection *observes*: the
 * `hello` frame of each new session maps to [ConnectionState.Connected], a socket close to
 * [ConnectionState.Connecting], a socket error to [ConnectionState.Backoff].
 *
 * The SDK is thread-based: listeners fire on its executor threads. They do exactly two non-blocking
 * things — ack the envelope (within Slack's 3s redelivery deadline) and `trySend` a [SlackSignal] into
 * an unbounded channel. A single drainer coroutine owns every `_state`/`_messages`/`_events` write, so
 * state transitions can't race [close] and envelope order is whatever the drainer receives.
 *
 * A revoked app token mid-session cycles Connecting/Backoff rather than reaching Failed —
 * distinguishing a fatal close is a later refinement, as in the Discord adapter.
 */
internal class SlackConnection(
    botToken: String,
    private val appToken: String,
    scope: CoroutineScope,
) : AdapterConnection {

    // Rendezvous like the other adapters: the gateway forwards into its own buffered flow.
    private val _messages = MutableSharedFlow<IncomingMessage>()
    private val _events = MutableSharedFlow<ChannelEvent>()
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)

    override val messages: Flow<IncomingMessage> = _messages.asSharedFlow()
    override val events: Flow<ChannelEvent> = _events.asSharedFlow()
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    // Own instance (the no-arg getInstance() is a JVM-wide singleton we must not close on teardown).
    private val slack = Slack.getInstance(SlackConfig())
    private val methods = slack.methods(botToken)
    private val gson = GsonFactory.createSnakeCase()

    // Unbounded so listeners never block the SDK thread and never drop; inbound rate is bounded by Slack.
    private val signals = CoroutineChannel<SlackSignal>(UNLIMITED)

    private val job: Job = scope.launch { run() }

    private suspend fun run() {
        handshake() ?: return
        val client = openSocketMode() ?: return
        try {
            registerListeners(client)
            client.connect() // async: the session reports back through the hello/close/error listeners
            coroutineScope { launch { drain() } }
        } finally {
            // NonCancellable: this runs *because* we were cancelled; a plain withContext would skip the close
            // and leak the SDK's executors and socket thread.
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { client.close() } // best-effort; the socket may already be gone
            }
        }
    }

    /** `auth.test` — Slack's whoAmI. A rejected bot token is fatal; transient failures back off and retry. */
    @Suppress("TooGenericExceptionCaught") // startup failures must surface as state, not crash the scope
    private suspend fun handshake(): AuthTestResponse? {
        while (true) {
            try {
                val response = runInterruptible(Dispatchers.IO) { methods.authTest(AuthTestRequest.builder().build()) }
                check(response.isOk) { "Slack auth.test failed: ${response.error}" }
                return response
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                if (!failure.isTransient()) {
                    _state.value = ConnectionState.Failed(failure)
                    return null
                }
                _state.value = ConnectionState.Backoff(BACKOFF, failure)
                delay(BACKOFF)
            }
        }
    }

    /** Opens the Socket Mode client (`apps.connections.open` is a network call). Bad app token is fatal. */
    @Suppress("TooGenericExceptionCaught") // startup failures must surface as state, not crash the scope
    private suspend fun openSocketMode(): SocketModeClient? {
        while (true) {
            try {
                return runInterruptible(Dispatchers.IO) {
                    slack.socketMode(appToken, SocketModeClient.Backend.JavaWebSocket)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                if (!failure.isTransient()) {
                    _state.value = ConnectionState.Failed(failure)
                    return null
                }
                _state.value = ConnectionState.Backoff(BACKOFF, failure)
                delay(BACKOFF)
            }
        }
    }

    private fun registerListeners(client: SocketModeClient) {
        client.addEventsApiEnvelopeListener { envelope ->
            // Ack first, within Slack's 3s deadline, so slow downstream work never triggers redelivery.
            ack(client, envelope)
            signals.trySend(SlackSignal.Envelope(envelope))
        }
        client.addWebSocketMessageListener { text ->
            if (isHelloFrame(gson, text)) signals.trySend(SlackSignal.Hello)
        }
        client.addWebSocketCloseListener { code, reason -> signals.trySend(SlackSignal.SocketClosed(code, reason)) }
        client.addWebSocketErrorListener { cause -> signals.trySend(SlackSignal.SocketError(cause)) }
    }

    @Suppress("TooGenericExceptionCaught") // a dying socket must not crash the SDK's listener thread
    private fun ack(client: SocketModeClient, envelope: EventsApiEnvelope) {
        try {
            client.sendSocketModeResponse(AckResponse.builder().envelopeId(envelope.envelopeId).build())
        } catch (failure: Exception) {
            // The session is going down mid-ack; Slack redelivers the envelope after the SDK reconnects.
            signals.trySend(SlackSignal.SocketError(failure))
        }
    }

    private suspend fun drain() {
        for (signal in signals) {
            signal.toConnectionState()?.let { _state.value = it }
            // SlackSignal.Envelope: normalization and emission land with the outbound slice.
        }
    }

    /** 429s, 5xx and transport failures are worth retrying; anything else (rejected token, SDK bug) is fatal. */
    private fun Exception.isTransient(): Boolean = when (this) {
        is SlackApiException -> isRetryable()
        is IOException -> (cause as? SlackApiException)?.isRetryable() ?: true
        else -> false
    }

    private fun SlackApiException.isRetryable(): Boolean =
        response.code == HTTP_TOO_MANY_REQUESTS || response.code >= HTTP_SERVER_ERROR

    override suspend fun close() {
        job.cancelAndJoin()
        withContext(Dispatchers.IO) {
            runCatching { slack.close() } // shuts the owned HTTP client and its executors down
        }
        _state.value = ConnectionState.Closed
    }

    private companion object {
        val BACKOFF = 5.seconds
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR = 500
    }
}

/** A callback from the SDK's own threads, funneled into [SlackConnection]'s single drainer coroutine. */
internal sealed interface SlackSignal {
    data class Envelope(val envelope: EventsApiEnvelope) : SlackSignal

    data object Hello : SlackSignal

    data class SocketClosed(val code: Int?, val reason: String?) : SlackSignal

    data class SocketError(val cause: Throwable) : SlackSignal
}

/** The [ConnectionState] a signal maps to; null for the payload-carrying signal. Pure, for unit tests. */
internal fun SlackSignal.toConnectionState(): ConnectionState? = when (this) {
    SlackSignal.Hello -> ConnectionState.Connected

    // The SDK owns reconnection (session monitor + Slack's `disconnect` frames): a closed socket is
    // already on its way back up; the next `hello` flips the state to Connected again.
    is SlackSignal.SocketClosed -> ConnectionState.Connecting

    // The SDK retries on its own ~5s schedule; the delay is a hint for observers, not our timer.
    is SlackSignal.SocketError -> ConnectionState.Backoff(SDK_RETRY_HINT, cause)

    is SlackSignal.Envelope -> null
}

private val SDK_RETRY_HINT = 5.seconds
