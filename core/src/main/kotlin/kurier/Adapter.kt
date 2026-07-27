package kurier

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

public sealed interface ConnectionState {
    /** Establishing (or re-establishing) the platform connection. */
    public data object Connecting : ConnectionState

    /** Live — messages and events are flowing. */
    public data object Connected : ConnectionState

    /** Transient failure; the adapter retries on its own, after roughly [retryIn]. */
    public data class Backoff(public val retryIn: Duration, public val cause: Throwable? = null) : ConnectionState

    /** Terminal: the adapter gave up (bad token, fatal rejection) — no automatic retry. */
    public data class Failed(public val cause: Throwable? = null) : ConnectionState

    /** Terminal: entered via [ChatGateway.stop] / [AdapterConnection.close]. */
    public data object Closed : ConnectionState
}

/**
 * A non-message happening in a channel (deletions, reactions, …).
 *
 * Deliberately **not** sealed: new event types (message edits, button interactions, membership
 * changes) are added in minor releases. Always include an `else` branch when matching on events —
 * unrecognized events must be safe to ignore.
 */
public interface ChannelEvent {
    public val channelId: ChannelId

    /** Platform-specific underlying object (escape hatch), where the adapter provides one. */
    public val raw: Any? get() = null

    public data class MessageDeleted(
        override val channelId: ChannelId,
        public val messageId: MessageId,
        override val raw: Any? = null,
    ) : ChannelEvent

    public data class ReactionAdded(
        override val channelId: ChannelId,
        public val messageId: MessageId,
        /** Canonical unicode (`"👍"`) where the platform's form maps to one; platform-custom emoji surface in native form. */
        public val emoji: String,
        public val author: Author,
        override val raw: Any? = null,
    ) : ChannelEvent

    public data class ReactionRemoved(
        override val channelId: ChannelId,
        public val messageId: MessageId,
        /** Canonical unicode (`"👍"`) where the platform's form maps to one; platform-custom emoji surface in native form. */
        public val emoji: String,
        public val author: Author,
        override val raw: Any? = null,
    ) : ChannelEvent
}

/**
 * The SPI a platform integration implements. Adapters wrap existing SDKs and
 * normalize to kurier's model — they own reconnection and rate limiting.
 */
public interface ChannelAdapter {
    public val platform: PlatformId

    /**
     * Returns immediately; all connection work (handshake, read loops, reconnection) is launched
     * into [scope], which bounds the connection's lifetime together with [AdapterConnection.close].
     * Jobs launched into [scope] must not let exceptions escape — contain failures and report them
     * via the connection's state.
     */
    public fun connect(scope: CoroutineScope): AdapterConnection
}

/**
 * A live platform connection, returned by [ChannelAdapter.connect]. The SPI contract every
 * implementation must honor:
 *
 * - [messages], [events], and [state] never complete exceptionally. Surface failures through
 *   [state] as [ConnectionState.Backoff]/[ConnectionState.Failed] and keep the flows alive (or
 *   complete them normally on shutdown) — the gateway treats an exceptional flow as that one
 *   platform failing, never as a reason to crash the host.
 * - [messages] and [events] are hot, non-replaying, and multicast-safe: collecting must never start
 *   per-collector work (the gateway collects them in independent coroutines), and emissions with no
 *   active subscriber may be dropped.
 * - The gateway subscribes to all three flows before [ChatGateway.start] returns. Anything emitted
 *   before that subscription — during [ChannelAdapter.connect], or by coroutines it launches that
 *   win the race — is silently dropped by unbuffered hot flows. A connection that produces data
 *   eagerly (in-process bridge, backlog replay) must not depend on subscription timing: buffer or
 *   replay its flows, or gate the first emission on `MutableSharedFlow.subscriptionCount`.
 */
public interface AdapterConnection {
    /**
     * Inbound messages from other users. Must NOT include the bot's own outgoing messages — most
     * platforms echo them back on the event stream, and an unfiltered adapter puts every bot in an
     * infinite self-reply loop. Filter by the authenticated self id.
     */
    public val messages: Flow<IncomingMessage>

    /** Channel events from other users; the bot's own reactions are excluded like [messages]. */
    public val events: Flow<ChannelEvent>

    /**
     * The connection lifecycle. Starts at [ConnectionState.Connecting]; [ConnectionState.Connected]
     * only after a successful handshake; transient drops fall back to Connecting or
     * [ConnectionState.Backoff] (the adapter retries on its own); [ConnectionState.Failed] is
     * terminal — the adapter has given up and must not retry; [ConnectionState.Closed] is terminal,
     * set by [close].
     */
    public val state: StateFlow<ConnectionState>

    /**
     * Resolves a channel for proactive sends. A connected adapter MUST return a channel for any
     * syntactically valid id of its own platform — per-send failures (unknown chat, no permission)
     * surface from the returned channel's `send`, not here. Null means only: a foreign platform
     * prefix, an unparseable native id, or the connection isn't established yet. Never throws.
     */
    public fun channel(id: ChannelId): Channel?

    /** Idempotent: cancels and joins the connection's work, releases owned clients, sets [state] to Closed. */
    public suspend fun close()
}
