package kurier.matrix

import kurier.ChannelId
import kurier.Content
import kurier.MessageId
import kurier.SentMessage
import net.folivo.trixnity.clientserverapi.client.MatrixClientServerApiClient
import net.folivo.trixnity.core.model.EventId
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.events.m.RelatesTo
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent

/**
 * A message kurier sent to a Matrix room. Editing isn't an endpoint on Matrix — it's a new event:
 * [edit] sends an `m.replace` carrying the new content (plus a `* …` fallback for clients that don't
 * render edits), which is also what MX-3 streaming drives per token. [delete] redacts the event.
 */
internal class MatrixSentMessage(
    private val client: MatrixClientServerApiClient,
    private val roomId: RoomId,
    private val eventId: EventId,
    override val channelId: ChannelId,
) : SentMessage {
    override val id: MessageId = MessageId(eventId.full)

    override suspend fun edit(content: Content) {
        val rendered = content.toMatrix()
        val replacement = RoomMessageEventContent.TextBased.Text(
            body = "* ${rendered.body}",
            format = rendered.formattedBody?.let { HTML_FORMAT },
            formattedBody = rendered.formattedBody?.let { "* $it" },
            relatesTo = RelatesTo.Replace(eventId, rendered.toText()),
        )
        client.room.sendMessageEvent(roomId, replacement).getOrThrow()
    }

    override suspend fun delete() {
        client.room.redactEvent(roomId, eventId).getOrThrow()
    }
}
