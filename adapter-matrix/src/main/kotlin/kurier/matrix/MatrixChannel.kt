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

/**
 * A Matrix room exposed as a kurier [Channel]. MX-1 carries identity only; [send] (MX-2) and
 * [sendStreaming] via `m.replace` edits (MX-3) land in later slices.
 */
internal class MatrixChannel(
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

    override suspend fun send(content: Content): SentMessage =
        TODO("MX-2: sendMessageEvent with body + HTML formatted_body")

    override suspend fun sendStreaming(tokens: Flow<String>, options: StreamingOptions): SentMessage =
        TODO("MX-3: sendStreamingByEditing via m.replace edit events")
}
