package kurier.slack

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
 * A Slack conversation exposed as a kurier [Channel]. [send] renders RichText to mrkdwn and posts it
 * via the [SlackSender] seam; [sendStreaming] reuses the shared edit engine, throttled to [MIN_EDIT_INTERVAL].
 */
internal class SlackChannel(
    private val sender: SlackSender,
    override val id: ChannelId,
    override val platform: PlatformId,
    override val kind: ChannelKind,
    override val name: String?,
) : Channel {

    override fun supports(capability: Capability): Boolean = when (capability) {
        Capability.EDITING,
        Capability.REACTIONS,
        -> true

        // THREADS is provisional false: a Slack thread is not addressable as a Channel (unlike Discord)
        // and core has no thread-targeted send yet — inbound replyTo still carries the thread ref.
        // The Web API has no typing indicator; Block Kit buttons and file uploads are deferred.
        Capability.THREADS,
        Capability.TYPING,
        Capability.BUTTONS,
        Capability.FILES,
        Capability.VOICE,
        -> false
    }

    override suspend fun send(content: Content): SentMessage =
        SlackSentMessage(sender, sender.send(content.toSlack()), id)

    override suspend fun sendStreaming(tokens: Flow<String>, options: StreamingOptions): SentMessage =
        sendStreamingByEditing(tokens, options, MIN_EDIT_INTERVAL)

    /**
     * Adds a reaction via `reactions.add`, best-effort per [kurier.IncomingMessage.react]'s contract:
     * unicode [emoji] translates to a Slack shortcode (ASCII input is already one); unmapped emoji
     * and platform rejections degrade to a no-op.
     */
    suspend fun react(messageId: MessageId, emoji: String) {
        val name = emojiToSlackName(emoji) ?: return
        try {
            sender.react(messageId, name)
        } catch (ignored: KurierException) {
        }
    }

    private companion object {
        // chat.update is Tier 3 (~50/min sustained); 1.2s is exactly 50/min, so a long streaming reply
        // can't trip `ratelimited` mid-stream — there is no retry layer above this to absorb one.
        val MIN_EDIT_INTERVAL: Duration = 1.2.seconds
    }
}
