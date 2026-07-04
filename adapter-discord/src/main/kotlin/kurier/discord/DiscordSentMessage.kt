package kurier.discord

import kurier.ChannelId
import kurier.Content
import kurier.MessageId
import kurier.SentMessage

/**
 * A message kurier sent to Discord. [edit] re-renders [Content] to Markdown — the same call streaming-edit
 * replies drive per token — and [delete] removes it, both routed through the [DiscordSender] seam by id.
 */
internal class DiscordSentMessage(
    private val sender: DiscordSender,
    override val id: MessageId,
    override val channelId: ChannelId,
) : SentMessage {

    override suspend fun edit(content: Content) {
        sender.edit(id, content.toDiscord())
    }

    override suspend fun delete() {
        sender.delete(id)
    }
}
