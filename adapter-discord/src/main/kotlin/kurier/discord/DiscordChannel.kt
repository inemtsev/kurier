package kurier.discord

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
 * A Discord channel exposed as a kurier [Channel]. B1 carries identity only so incoming messages
 * can be normalized and routed; [send]/[sendStreaming] land in B2 (reusing the shared edit engine).
 */
internal class DiscordChannel(
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
        Capability.THREADS,
        Capability.BUTTONS,
        -> true

        Capability.VOICE -> false
    }

    override suspend fun send(content: Content): SentMessage =
        TODO("B2: createMessage with escaped-markdown rendering")

    override suspend fun sendStreaming(tokens: Flow<String>, options: StreamingOptions): SentMessage =
        TODO("B2: sendStreamingByEditing with Discord's edit interval")
}
