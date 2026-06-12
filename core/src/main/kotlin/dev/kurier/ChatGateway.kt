package dev.kurier

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The application-facing entry point: all installed adapters merged behind
 * one platform-agnostic API.
 */
public interface ChatGateway {
    /** Incoming messages from every connected platform, merged. */
    public val messages: Flow<IncomingMessage>

    /** Non-message events (deletions, reactions, …) from every platform, merged. */
    public val events: Flow<ChannelEvent>

    /** Connection state per platform. */
    public val connections: StateFlow<Map<PlatformId, ConnectionState>>

    public suspend fun start()
    public suspend fun stop()

    /** Resolves a channel for proactive sends (alerts, scheduled messages). */
    public fun channel(id: ChannelId): Channel?
}
