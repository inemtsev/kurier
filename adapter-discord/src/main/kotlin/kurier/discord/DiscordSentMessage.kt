package kurier.discord

import dev.kord.core.behavior.edit
import dev.kord.core.entity.Message
import kurier.ChannelId
import kurier.Content
import kurier.MessageId
import kurier.SentMessage

/**
 * A message kurier sent to Discord, wrapping the Kord [Message]. [edit] re-renders [Content] to
 * Markdown — the same call streaming-edit replies drive per token — and [delete] removes it.
 */
internal class DiscordSentMessage(
    private val message: Message,
    override val channelId: ChannelId,
) : SentMessage {
    override val id: MessageId = MessageId(message.id.toString())

    override suspend fun edit(content: Content) {
        val rendered = content.toDiscord()
        message.edit { this.content = rendered }
    }

    override suspend fun delete() {
        message.delete()
    }
}
