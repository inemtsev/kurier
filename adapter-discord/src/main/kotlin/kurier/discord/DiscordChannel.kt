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
import kurier.sendStreamingByEditing
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A Discord channel exposed as a kurier [Channel]. [send] renders RichText to Markdown and posts it via
 * the [DiscordSender] seam; [sendStreaming] reuses the shared edit engine, throttled to [MIN_EDIT_INTERVAL].
 */
internal class DiscordChannel(
    private val sender: DiscordSender,
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
        DiscordSentMessage(sender, sender.send(content.toDiscord()), id)

    override suspend fun sendStreaming(tokens: Flow<String>, options: StreamingOptions): SentMessage =
        sendStreamingByEditing(tokens, options, MIN_EDIT_INTERVAL)

    override suspend fun indicateTyping() {
        sender.typing()
    }

    private companion object {
        // Discord allows ~5 edits / 5s per channel (≈1/s sustained); Kord auto-rate-limits as a backstop.
        val MIN_EDIT_INTERVAL: Duration = 1.seconds
    }
}
