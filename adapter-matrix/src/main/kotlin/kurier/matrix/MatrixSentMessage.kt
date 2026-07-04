package kurier.matrix

import kurier.ChannelId
import kurier.Content
import kurier.MessageId
import kurier.SentMessage

/**
 * A message kurier sent to a Matrix room. Editing isn't an endpoint on Matrix — it's a new event — so
 * [edit] sends an `m.replace` (the same call MX-3 streaming drives per token) and [delete] redacts the
 * event; both route through the [MatrixSender] seam by id.
 */
internal class MatrixSentMessage(
    private val sender: MatrixSender,
    override val id: MessageId,
    override val channelId: ChannelId,
) : SentMessage {

    override suspend fun edit(content: Content) {
        sender.replace(id, content.toMatrix())
    }

    override suspend fun delete() {
        sender.redact(id)
    }
}
