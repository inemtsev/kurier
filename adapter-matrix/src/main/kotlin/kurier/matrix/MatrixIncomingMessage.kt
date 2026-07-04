package kurier.matrix

import kurier.Attachment
import kurier.Author
import kurier.Channel
import kurier.ChannelId
import kurier.ChannelKind
import kurier.IncomingMessage
import kurier.MessageId
import kurier.MessageRef
import kurier.PlatformId
import kurier.RichText
import net.folivo.trixnity.core.model.events.ClientEvent
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent

/**
 * A Matrix room message normalized into kurier's [IncomingMessage]. The Trixnity event stays on
 * [raw]. MX-1 maps the plain `body`; HTML `formatted_body` → RichText parsing is deferred to M3,
 * as are attachments (media msgtypes), reply refs, and display-name lookup (needs room state).
 */
internal class MatrixIncomingMessage(
    override val id: MessageId,
    override val channel: Channel,
    override val author: Author,
    override val rich: RichText,
    override val isDirectedAtBot: Boolean,
    override val raw: Any,
) : IncomingMessage {
    override val attachments: List<Attachment> = emptyList()
    override val replyTo: MessageRef? = null

    // The channel is always a MatrixChannel here; it routes the reaction through its sender seam.
    override suspend fun react(emoji: String) {
        (channel as MatrixChannel).react(id, emoji)
    }
}

internal fun <C : RoomMessageEventContent> ClientEvent.RoomEvent<C>.toIncomingMessage(
    platform: PlatformId,
    session: MatrixSession,
): IncomingMessage {
    val channel = MatrixChannel(
        sender = TrixnityMatrixSender(session, roomId),
        id = ChannelId("${platform.value}:${roomId.full}"),
        platform = platform,
        // Matrix doesn't distinguish DM vs group at this layer (that's m.direct account data); default GROUP.
        kind = ChannelKind.GROUP,
        name = null,
    )
    return MatrixIncomingMessage(
        id = MessageId(id.full),
        channel = channel,
        author = Author(sender.full),
        rich = RichText.plain(content.body),
        isDirectedAtBot = directedAtBot(session.self.full, content.body, content.formattedBody),
        raw = this,
    )
}

/**
 * Heuristic: Matrix has no clean per-message mention list at this layer, so treat a message as
 * directed if it names the bot's mxid in the plain body or the HTML pill (`matrix.to/#/<mxid>`).
 * Refined in a later slice (DM rooms, `m.mentions`, reply-to-bot).
 */
internal fun directedAtBot(selfMxid: String, body: String, formattedBody: String?): Boolean =
    body.contains(selfMxid) || formattedBody?.contains(selfMxid) == true
