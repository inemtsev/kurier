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
import kurier.RichNode
import kurier.RichText

/**
 * Wraps a Telegram [Message] as a kurier [IncomingMessage], deriving kurier's fields
 * up front from the source DTO, which stays reachable on [raw] as the escape hatch.
 */
internal class TelegramIncomingMessage(
    private val source: Message,
    private val api: TelegramApi,
    override val channel: Channel,
    override val isDirectedAtBot: Boolean,
) : IncomingMessage {
    override val id: MessageId = MessageId(source.messageId.toString())
    override val author: Author = source.toAuthor()
    override val rich: RichText = source.toRichText()
    override val attachments: List<Attachment> = source.toAttachments()
    override val replyTo: MessageRef? =
        source.replyToMessage?.let { MessageRef(channel.id, MessageId(it.messageId.toString())) }
    override val raw: Any = source

    override suspend fun react(emoji: String) {
        api.setMessageReaction(source.chat.id, source.messageId, emoji)
    }
}

/**
 * Normalizes a Telegram [Message] into kurier's [IncomingMessage]: rich text, author
 * (incl. sender_chat for channel posts), channel identity, reply refs, photo/document
 * attachments, and directedness (DM, reply-to-bot, or @-mention). Deferred to M3:
 * nested-formatting trees, more media types, and resolving attachment download urls.
 */
internal fun Message.toIncomingMessage(platform: PlatformId, api: TelegramApi, bot: User): IncomingMessage {
    val channel = TelegramChannel(
        chatId = chat.id,
        api = api,
        id = ChannelId("${platform.value}:${chat.id}"),
        platform = platform,
        kind = chat.type.toChannelKind(),
        name = chat.title ?: chat.username,
    )
    return TelegramIncomingMessage(
        source = this,
        api = api,
        channel = channel,
        isDirectedAtBot = isDirectedAt(bot),
    )
}

// DM, a reply to one of the bot's own messages, or an @-mention / text-mention of the bot.
private fun Message.isDirectedAt(bot: User): Boolean =
    chat.type == PRIVATE_CHAT ||
        replyToMessage?.from?.id == bot.id ||
        mentionsBot(bot)

private fun Message.mentionsBot(bot: User): Boolean {
    val content = text ?: return false
    return entities.orEmpty().any { entity ->
        when (entity.type) {
            "mention" -> bot.username != null && content.span(entity) == "@${bot.username}"
            "text_mention" -> entity.user?.id == bot.id
            else -> false
        }
    }
}

/** Telegram entities → [RichText]; unmapped types degrade to plain [RichNode.Text] so no text is lost. */
private fun Message.toRichText(): RichText {
    val content = text ?: return RichText(emptyList())
    val nodes = mutableListOf<RichNode>()
    var cursor = 0
    for (entity in entities.orEmpty().sortedBy { it.offset }) {
        // skip out-of-bounds spans and overlapping/nested ones (keep the outer span)
        val value = content.span(entity)
        if (value == null || entity.offset < cursor) continue
        if (entity.offset > cursor) nodes += RichNode.Text(content.substring(cursor, entity.offset))
        nodes += entity.toNode(value)
        cursor = entity.offset + entity.length
    }
    if (cursor < content.length) nodes += RichNode.Text(content.substring(cursor))
    return RichText(nodes)
}

private fun MessageEntity.toNode(value: String): RichNode = when (type) {
    "bold" -> RichNode.Bold(listOf(RichNode.Text(value)))
    "italic" -> RichNode.Italic(listOf(RichNode.Text(value)))
    "code" -> RichNode.Code(value)
    "pre" -> RichNode.CodeBlock(value, language)
    "text_link" -> RichNode.Link(url ?: value, value)
    "url" -> RichNode.Link(value)
    else -> RichNode.Text(value)
}

/** Bounds-safe substring for an entity's offset/length (UTF-16 units, which Kotlin strings already use). */
private fun String.span(entity: MessageEntity): String? {
    val end = entity.offset + entity.length
    return if (entity.offset in 0..length && end in entity.offset..length) substring(entity.offset, end) else null
}

private const val PRIVATE_CHAT = "private"

private fun String.toChannelKind(): ChannelKind = when (this) {
    PRIVATE_CHAT -> ChannelKind.DM
    "channel" -> ChannelKind.BROADCAST
    else -> ChannelKind.GROUP // group, supergroup
}

private fun Message.toAuthor(): Author = when {
    from != null -> Author(from.id.toString(), from.displayName(), from.isBot)
    senderChat != null -> Author(senderChat.id.toString(), senderChat.title ?: senderChat.username)
    else -> Author("unknown")
}

private fun User.displayName(): String? =
    listOfNotNull(firstName, lastName).joinToString(" ").ifEmpty { username }

// Largest photo (the array is smallest→largest) and any document, as platform-handle attachments.
// Resolving each id to a download url needs a getFile round-trip — a future enhancement.
private fun Message.toAttachments(): List<Attachment> = buildList {
    document?.let { add(Attachment(fileName = it.fileName, contentType = it.mimeType, id = it.fileId)) }
    photo?.lastOrNull()?.let { add(Attachment(contentType = "image/jpeg", id = it.fileId)) }
}
