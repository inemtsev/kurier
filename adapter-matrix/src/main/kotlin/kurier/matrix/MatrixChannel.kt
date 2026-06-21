package kurier.matrix

import kotlinx.coroutines.flow.Flow
import kurier.Capability
import kurier.Channel
import kurier.ChannelId
import kurier.ChannelKind
import kurier.Content
import kurier.PlatformId
import kurier.SentMessage
import kurier.StreamingOptions
import kurier.sendStreamingByEditing
import net.folivo.trixnity.core.model.EventId
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.events.m.ReactionEventContent
import net.folivo.trixnity.core.model.events.m.RelatesTo
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A Matrix room exposed as a kurier [Channel]. [send] renders to body + HTML `formatted_body`;
 * [sendStreaming] reuses the shared edit engine over `m.replace` edits, throttled to [MIN_EDIT_INTERVAL].
 */
internal class MatrixChannel(
    private val session: MatrixSession,
    private val roomId: RoomId,
    override val id: ChannelId,
    override val platform: PlatformId,
    override val kind: ChannelKind,
    override val name: String?,
) : Channel {

    override fun supports(capability: Capability): Boolean = when (capability) {
        Capability.EDITING,
        Capability.REACTIONS,
        Capability.FILES,
        Capability.TYPING,
        -> true

        // Threads (m.thread) and voice exist in Matrix but aren't wired yet; revisit in M3.
        Capability.THREADS,
        Capability.VOICE,
        -> false

        // Matrix has no native interactive buttons/components — this stays false.
        Capability.BUTTONS,
        -> false
    }

    override suspend fun send(content: Content): SentMessage {
        val eventId = session.client.room.sendMessageEvent(roomId, content.toMatrix().toText()).getOrThrow()
        return MatrixSentMessage(session.client, roomId, eventId, id)
    }

    override suspend fun sendStreaming(tokens: Flow<String>, options: StreamingOptions): SentMessage =
        sendStreamingByEditing(tokens, options, MIN_EDIT_INTERVAL)

    override suspend fun indicateTyping() {
        session.client.room.setTyping(roomId, session.self, typing = true, timeout = TYPING_TIMEOUT_MS).getOrThrow()
    }

    /** Annotates [eventId] with [emoji] — an `m.reaction` event. Used by `IncomingMessage.react`. */
    suspend fun react(eventId: EventId, emoji: String) {
        val reaction = ReactionEventContent(RelatesTo.Annotation(eventId, key = emoji))
        session.client.room.sendMessageEvent(roomId, reaction).getOrThrow()
    }

    private companion object {
        // Matrix edit rate limits are lenient, but each edit is a full m.replace event; ~1/s is safe.
        val MIN_EDIT_INTERVAL: Duration = 1.seconds
        val TYPING_TIMEOUT_MS: Long = 15.seconds.inWholeMilliseconds
    }
}
