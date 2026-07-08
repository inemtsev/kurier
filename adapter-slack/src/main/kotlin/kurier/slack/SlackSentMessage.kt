package kurier.slack

import kurier.ChannelId
import kurier.Content
import kurier.MessageId
import kurier.SentMessage

/**
 * A message kurier sent to Slack. [edit] re-renders [Content] to mrkdwn via `chat.update` — the same
 * call streaming-edit replies drive per token — and [delete] is `chat.delete`, both through the
 * [SlackSender] seam by `ts` (Slack's message id).
 */
internal class SlackSentMessage(
    private val sender: SlackSender,
    override val id: MessageId,
    override val channelId: ChannelId,
) : SentMessage {

    override suspend fun edit(content: Content) {
        sender.edit(id, content.toSlack())
    }

    override suspend fun delete() {
        sender.delete(id)
    }
}
