package kurier.matrix

import kotlinx.coroutines.flow.Flow
import kurier.Capability
import kurier.Channel
import kurier.ChannelId
import kurier.ChannelKind
import kurier.Content
import kurier.KurierException
import kurier.MessageId
import kurier.PlatformId
import kurier.SentMessage
import kurier.StreamingOptions
import kurier.sendStreamingByEditing
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A Matrix room exposed as a kurier [Channel]. [send] renders to body + HTML `formatted_body`;
 * [sendStreaming] reuses the shared edit engine over `m.replace` edits, throttled to [MIN_EDIT_INTERVAL].
 * All platform I/O goes through the [MatrixSender] seam so the channel stays unit-testable.
 */
internal class MatrixChannel(
    private val sender: MatrixSender,
    override val id: ChannelId,
    override val platform: PlatformId,
    override val kind: ChannelKind,
    override val name: String?,
) : Channel {

    override fun supports(capability: Capability): Boolean = when (capability) {
        Capability.EDITING,
        Capability.REACTIONS,
        Capability.TYPING,
        -> true

        // Provisional: media upload (FILES), threads (m.thread), and voice exist in Matrix but
        // aren't wired yet — outbound attachments would be silently dropped today.
        Capability.FILES,
        Capability.THREADS,
        Capability.VOICE,
        -> false

        // Matrix has no native interactive buttons/components — this stays false.
        Capability.BUTTONS,
        -> false
    }

    override suspend fun send(content: Content): SentMessage =
        MatrixSentMessage(sender, sender.send(content.toMatrix()), id)

    override suspend fun sendStreaming(tokens: Flow<String>, options: StreamingOptions): SentMessage =
        sendStreamingByEditing(tokens, options, MIN_EDIT_INTERVAL)

    override suspend fun indicateTyping() {
        sender.typing()
    }

    /**
     * Annotates [messageId] with [emoji] — an `m.reaction` event. Used by `IncomingMessage.react`;
     * best-effort per its contract, so homeserver rejections degrade to a no-op.
     */
    suspend fun react(messageId: MessageId, emoji: String) {
        try {
            sender.react(messageId, emoji)
        } catch (ignored: KurierException) {
        }
    }

    private companion object {
        // Matrix edit rate limits are lenient, but each edit is a full m.replace event; ~1/s is safe.
        val MIN_EDIT_INTERVAL: Duration = 1.seconds
    }
}
