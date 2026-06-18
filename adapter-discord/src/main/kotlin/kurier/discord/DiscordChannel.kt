package kurier.discord

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.channel.MessageChannelBehavior
import dev.kord.core.behavior.channel.createMessage
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
 * A Discord channel exposed as a kurier [Channel]. [send] renders RichText to Markdown via the Bot
 * API; [sendStreaming] reuses the shared edit engine, throttled to Discord's [MIN_EDIT_INTERVAL].
 */
internal class DiscordChannel(
    private val kord: Kord,
    private val channelId: Snowflake,
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

    override suspend fun send(content: Content): SentMessage {
        val rendered = content.toDiscord()
        val message = MessageChannelBehavior(channelId, kord).createMessage { this.content = rendered }
        return DiscordSentMessage(message, id)
    }

    override suspend fun sendStreaming(tokens: Flow<String>, options: StreamingOptions): SentMessage =
        sendStreamingByEditing(tokens, options, MIN_EDIT_INTERVAL)

    override suspend fun indicateTyping() {
        MessageChannelBehavior(channelId, kord).type()
    }

    private companion object {
        // Discord allows ~5 edits / 5s per channel (≈1/s sustained); Kord auto-rate-limits as a backstop.
        val MIN_EDIT_INTERVAL: Duration = 1.seconds
    }
}
