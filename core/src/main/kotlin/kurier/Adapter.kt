package kurier

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

public sealed interface ConnectionState {
    public data object Connecting : ConnectionState
    public data object Connected : ConnectionState
    public data class Backoff(public val retryIn: Duration, public val cause: Throwable? = null) : ConnectionState
    public data class Failed(public val cause: Throwable? = null) : ConnectionState
    public data object Closed : ConnectionState
}

public sealed interface ChannelEvent {
    public val channelId: ChannelId

    public data class MessageDeleted(override val channelId: ChannelId, public val messageId: MessageId) : ChannelEvent

    public data class ReactionAdded(
        override val channelId: ChannelId,
        public val messageId: MessageId,
        public val emoji: String,
        public val by: Author,
    ) : ChannelEvent
}

/**
 * The SPI a platform integration implements. Adapters wrap existing SDKs and
 * normalize to kurier's model — they own reconnection and rate limiting.
 */
public interface ChannelAdapter {
    public val platform: PlatformId

    /** Pass in the scope to control the lifetime of the connection. */
    public fun connect(scope: CoroutineScope): AdapterConnection
}

public interface AdapterConnection {
    public val messages: Flow<IncomingMessage>
    public val events: Flow<ChannelEvent>
    public val state: StateFlow<ConnectionState>

    /** Resolves a known channel for proactive sends; null if unknown to this adapter. */
    public fun channel(id: ChannelId): Channel? = null

    public suspend fun close()
}
