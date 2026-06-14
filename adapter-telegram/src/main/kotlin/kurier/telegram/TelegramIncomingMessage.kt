package kurier.telegram

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

/**
 * Wraps a Telegram [Message] as a kurier [IncomingMessage], deriving kurier's fields
 * up front from the source DTO, which stays reachable on [raw] as the escape hatch.
 */
internal class TelegramIncomingMessage(
    private val source: Message,
    override val channel: Channel,
    override val isDirectedAtBot: Boolean,
) : IncomingMessage {
    override val id: MessageId = MessageId(source.messageId.toString())
    override val author: Author = source.from.toAuthor()
    override val rich: RichText = RichText.plain(source.text.orEmpty())
    override val attachments: List<Attachment> = emptyList()
    override val replyTo: MessageRef? =
        source.replyToMessage?.let { MessageRef(channel.id, MessageId(it.messageId.toString())) }
    override val raw: Any = source
}

/**
 * Normalizes a Telegram [Message] into kurier's [IncomingMessage].
 *
 * M1 minimal mapping: plain-text body, basic author/channel identity, and reply
 * refs. Deferred to slice 4: entity → [RichText] formatting, attachments, group
 * @-mention / reply-to-bot detection (for now only DMs count as directed), and
 * richer author attribution (last_name, plus sender_chat for channel posts).
 */
internal fun Message.toIncomingMessage(platform: PlatformId): IncomingMessage {
    val channel = TelegramChannel(
        id = ChannelId("${platform.value}:${chat.id}"),
        platform = platform,
        kind = chat.type.toChannelKind(),
        name = chat.title ?: chat.username,
    )
    return TelegramIncomingMessage(
        source = this,
        channel = channel,
        isDirectedAtBot = chat.type == PRIVATE_CHAT,
    )
}

private const val PRIVATE_CHAT = "private"

private fun String.toChannelKind(): ChannelKind = when (this) {
    PRIVATE_CHAT -> ChannelKind.DM
    "channel" -> ChannelKind.BROADCAST
    else -> ChannelKind.GROUP // group, supergroup
}

private fun User?.toAuthor(): Author = Author(
    id = this?.id?.toString() ?: "unknown",
    displayName = this?.let { it.firstName ?: it.username },
    isBot = this?.isBot ?: false,
)
