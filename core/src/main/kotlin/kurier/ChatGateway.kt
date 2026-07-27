package kurier

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The application-facing entry point: all installed adapters merged behind
 * one platform-agnostic API.
 *
 * Implemented by `kurier-runtime`'s `chatGateway {}`; not intended for third-party implementation —
 * to test bot logic, install a `FakeAdapter` from `kurier-testing` into a real gateway rather than
 * faking this interface. New members may be added in minor releases, with default implementations
 * where possible.
 */
public interface ChatGateway {
    /**
     * Incoming messages from every connected platform, merged. The delivery contract: hot, no
     * replay — each collector receives messages from its subscription onward, and messages arriving
     * while no collector is active are dropped, so begin collecting before (or immediately after)
     * [start]. Under sustained collector slowness delivery backpressures losslessly, which means a
     * sequential handler stalls inbound from every platform — launch a coroutine per message for
     * slow work (replies, LLM calls). Never completes, not even after [stop]; end collection by
     * cancelling the collecting coroutine.
     */
    public val messages: Flow<IncomingMessage>

    /**
     * Non-message events (deletions, reactions, …) from every platform, merged.
     * Same delivery contract as [messages]: hot, no replay, lossless backpressure, never completes.
     */
    public val events: Flow<ChannelEvent>

    /** Connection state per platform. */
    public val connections: StateFlow<Map<PlatformId, ConnectionState>>

    /**
     * Connects every installed adapter. Throws [IllegalStateException] if already started;
     * a gateway may be started again after [stop].
     */
    public suspend fun start()

    /**
     * Closes every connection and marks each platform [ConnectionState.Closed] in [connections].
     * Does not complete [messages]/[events] — cancel their collectors to end collection.
     */
    public suspend fun stop()

    /**
     * Resolves a channel for proactive sends (alerts, scheduled messages). Non-null for any
     * syntactically valid id of a connected platform; per-send failures surface from the returned
     * channel's `send`. To discover ids, log `message.channel.id` — the format is
     * `<platform>:<native id>` (see [ChannelId]).
     */
    public fun channel(id: ChannelId): Channel?
}
