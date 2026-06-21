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
import net.folivo.trixnity.clientserverapi.client.MatrixClientServerApiClient
import net.folivo.trixnity.core.model.RoomId

/**
 * A Matrix room exposed as a kurier [Channel]. [send] renders to body + HTML `formatted_body`;
 * [sendStreaming] via `m.replace` edits lands in MX-3 (reusing the shared edit engine).
 */
internal class MatrixChannel(
    private val client: MatrixClientServerApiClient,
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
        val eventId = client.room.sendMessageEvent(roomId, content.toMatrix().toText()).getOrThrow()
        return MatrixSentMessage(client, roomId, eventId, id)
    }

    override suspend fun sendStreaming(tokens: Flow<String>, options: StreamingOptions): SentMessage =
        TODO("MX-3: sendStreamingByEditing via m.replace edit events")
}
