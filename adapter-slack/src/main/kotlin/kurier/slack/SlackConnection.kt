package kurier.slack

import com.slack.api.Slack
import com.slack.api.SlackConfig
import com.slack.api.methods.SlackApiException
import com.slack.api.methods.request.auth.AuthTestRequest
import com.slack.api.methods.response.auth.AuthTestResponse
import com.slack.api.model.event.MessageDeletedEvent
import com.slack.api.model.event.MessageEvent
import com.slack.api.model.event.ReactionAddedEvent
import com.slack.api.model.event.ReactionRemovedEvent
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
import kurier.Author
import kurier.Channel
import kurier.ChannelEvent
import kurier.ChannelEvent.MessageDeleted
import kurier.ChannelEvent.ReactionAdded
import kurier.ChannelEvent.ReactionRemoved
import kurier.ChannelId
import kurier.ChannelKind
import kurier.ConnectionState
import kurier.IncomingMessage
import kurier.MessageId
import kurier.PlatformId
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
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
    private val platform: PlatformId,
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

    // Touched only by the drainer coroutine.
    private val seenEnvelopes = RecentIds(DEDUP_CAPACITY)

    private val job: Job = scope.launch { run() }

    private suspend fun run() {
        val self = handshake() ?: return
        val client = openSocketMode() ?: return
        try {
            registerListeners(client)
            client.connect() // async: the session reports back through the hello/close/error listeners
            coroutineScope { launch { drain(self) } }
        } finally {
            // NonCancellable: this runs *because* we were cancelled; a plain withContext would skip the close
            // and leak the SDK's executors and socket thread.
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { client.close() } // best-effort; the socket may already be gone
                // A monitor tick already past its auto-reconnect check can reconnect *during* close();
                // this trailing disconnect closes the session such a racer leaves behind. (A racer still
                // mid-connect is a narrow residual window — Slack force-closes never-acked sessions.)
                runCatching { client.disconnect() }
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
                val pending = AtomicReference<SocketModeClient>()
                try {
                    return runInterruptible(Dispatchers.IO) {
                        slack.socketMode(appToken, SocketModeClient.Backend.JavaWebSocket).also(pending::set)
                    }
                } catch (cancellation: CancellationException) {
                    // The client may finish constructing right as cancellation lands, in which case the
                    // return value is dropped — but its already-started session monitor would open a real
                    // connection 5s later and keep it alive for the JVM's lifetime. Close the orphan.
                    withContext(NonCancellable + Dispatchers.IO) { pending.get()?.let { runCatching(it::close) } }
                    throw cancellation
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
            // A failed ack means Slack will redeliver — process only the redelivery, or the bot acts twice.
            if (ack(client, envelope)) signals.trySend(SlackSignal.Envelope(envelope))
        }
        client.addWebSocketMessageListener { text ->
            if (isHelloFrame(gson, text)) signals.trySend(SlackSignal.Hello)
        }
        client.addWebSocketCloseListener { code, reason -> signals.trySend(SlackSignal.SocketClosed(code, reason)) }
        client.addWebSocketErrorListener { cause -> signals.trySend(SlackSignal.SocketError(cause)) }
    }

    @Suppress("TooGenericExceptionCaught") // a dying socket must not crash the SDK's listener thread
    private fun ack(client: SocketModeClient, envelope: EventsApiEnvelope): Boolean =
        try {
            client.sendSocketModeResponse(AckResponse.builder().envelopeId(envelope.envelopeId).build())
            true
        } catch (failure: Exception) {
            // The session is going down mid-ack; Slack redelivers the envelope after the SDK reconnects.
            signals.trySend(SlackSignal.SocketError(failure))
            false
        }

    private suspend fun drain(self: AuthTestResponse) {
        for (signal in signals) {
            signal.toConnectionState()?.let { _state.value = it }
            if (signal is SlackSignal.Envelope) handleEnvelope(self, signal.envelope)
        }
    }

    @Suppress("TooGenericExceptionCaught") // one payload we can't decode must not take the connection down
    private suspend fun handleEnvelope(self: AuthTestResponse, envelope: EventsApiEnvelope) {
        // A redelivery (same envelope id, retry_attempt bumped — e.g. our ack was lost in transit)
        // must not make the bot act twice.
        if (!seenEnvelopes.remember(envelope.envelopeId)) return
        try {
            when (val action = parseEnvelope(gson, envelope.payload)) {
                is SlackEventAction.Message -> onMessage(self, action.event)
                is SlackEventAction.Deleted -> deletedEvent(platform, action.event)?.let { _events.emit(it) }

                is SlackEventAction.ReactionAdded ->
                    reactionEvent(platform, self.userId, action.event.reactionInfo(), ::ReactionAdded)
                        ?.let { _events.emit(it) }

                is SlackEventAction.ReactionRemoved ->
                    reactionEvent(platform, self.userId, action.event.reactionInfo(), ::ReactionRemoved)
                        ?.let { _events.emit(it) }

                SlackEventAction.Ignore -> Unit
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (ignored: Exception) {
            // Dropped; Slack payloads occasionally grow shapes gson can't map onto the SDK models.
        }
    }

    private suspend fun onMessage(self: AuthTestResponse, event: MessageEvent) {
        // The event stream echoes the bot's own posts; drop them so the bot never replies to itself.
        if (event.isFrom(selfUserId = self.userId, selfBotId = self.botId)) return
        _messages.emit(event.toIncomingMessage(methods, platform, self.userId))
    }

    override fun channel(id: ChannelId): Channel? {
        val native = nativeIdOrNull(platform, id) ?: return null
        // Proactive send: kind isn't known without a conversations.info fetch; DM channel ids start with D.
        val kind = if (native.startsWith("D")) ChannelKind.DM else ChannelKind.GROUP
        return SlackChannel(MethodsSlackSender(methods, native), id, platform, kind, name = null)
    }

    override suspend fun close() {
        job.cancelAndJoin()
        withContext(Dispatchers.IO) {
            runCatching { slack.close() } // shuts the owned HTTP client and its executors down
        }
        _state.value = ConnectionState.Closed
    }

    private companion object {
        val BACKOFF = 5.seconds
        const val DEDUP_CAPACITY = 256
    }
}

/**
 * Remembers the last [capacity] ids so a redelivered envelope is processed exactly once. Not
 * thread-safe by design — only the drainer coroutine touches it.
 */
internal class RecentIds(private val capacity: Int) {
    private val order = ArrayDeque<String>()
    private val seen = HashSet<String>()

    /** True the first time [id] is seen (or when null — never deduplicated); false on a repeat. */
    fun remember(id: String?): Boolean {
        if (id == null || !seen.add(id)) return id == null
        order.addLast(id)
        if (order.size > capacity) seen.remove(order.removeFirst())
        return true
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

private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR = 500

private fun nativeIdOrNull(platform: PlatformId, id: ChannelId): String? {
    if (id.value.substringBefore(':') != platform.value) return null
    return id.value.substringAfter(':').takeIf { it.isNotBlank() }
}

/** The wire fields of a reaction event, decoupled from the SDK's two distinct reaction event classes. */
internal data class SlackReactionInfo(
    val userId: String?,
    val channelId: String?,
    val messageTs: String?,
    val name: String?,
)

private fun ReactionAddedEvent.reactionInfo(): SlackReactionInfo =
    SlackReactionInfo(user, item?.channel, item?.ts, reaction)

private fun ReactionRemovedEvent.reactionInfo(): SlackReactionInfo =
    SlackReactionInfo(user, item?.channel, item?.ts, reaction)

/**
 * Builds the [ChannelEvent] for a reaction, or null when it is the bot's own reaction (the stream
 * echoes those back) or when it targets something other than a message (file reactions carry no
 * channel/ts). The emoji stays a Slack shortcode (`"thumbsup"`). Pure, so the mapping and self-filter
 * are unit-testable without a socket.
 */
internal fun reactionEvent(
    platform: PlatformId,
    selfUserId: String,
    info: SlackReactionInfo,
    build: (ChannelId, MessageId, String, Author) -> ChannelEvent,
): ChannelEvent? {
    if (info.userId == null || info.userId == selfUserId) return null
    return if (info.channelId != null && info.messageTs != null && info.name != null) {
        build(
            ChannelId("${platform.value}:${info.channelId}"),
            MessageId(info.messageTs),
            info.name,
            Author(info.userId),
        )
    } else {
        null
    }
}

/** Builds the deletion event, or null when the payload lacks its coordinates. Pure, for unit tests. */
internal fun deletedEvent(platform: PlatformId, event: MessageDeletedEvent): ChannelEvent? =
    if (event.channel == null || event.deletedTs == null) {
        null
    } else {
        MessageDeleted(ChannelId("${platform.value}:${event.channel}"), MessageId(event.deletedTs))
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
