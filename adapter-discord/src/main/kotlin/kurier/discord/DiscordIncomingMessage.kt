package kurier.discord

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.entity.ReactionEmoji
import dev.kord.core.entity.User
import dev.kord.core.event.message.MessageCreateEvent
import kotlinx.coroutines.CancellationException
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
 * Wraps a Discord [MessageCreateEvent] as a kurier [IncomingMessage]. The Kord event stays on
 * [raw] as the escape hatch. B1 maps content as plain text; Markdown → RichText parsing is M3.
 */
internal class DiscordIncomingMessage(
    private val event: MessageCreateEvent,
    override val channel: Channel,
    override val author: Author,
    override val isDirectedAtBot: Boolean,
) : IncomingMessage {
    private val message get() = event.message
    override val id: MessageId = MessageId(message.id.toString())
    override val rich: RichText = RichText.plain(message.content)
    override val attachments: List<Attachment> = message.attachments.map {
        Attachment(fileName = it.filename, contentType = it.contentType, url = it.url)
    }
    override val replyTo: MessageRef? =
        message.referencedMessage?.let { MessageRef(channel.id, MessageId(it.id.toString())) }
    override val raw: Any = event

    @Suppress("TooGenericExceptionCaught") // raw Kord call: any rejection degrades to a no-op (capability rule 6)
    override suspend fun react(emoji: String) {
        try {
            message.addReaction(ReactionEmoji.Unicode(emoji))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (ignored: Exception) {
        }
    }
}

internal fun MessageCreateEvent.toIncomingMessage(platform: PlatformId, selfId: Snowflake, kord: Kord): IncomingMessage {
    val channel = DiscordChannel(
        sender = KordDiscordSender(kord, message.channelId),
        id = ChannelId("${platform.value}:${message.channelId}"),
        platform = platform,
        // No guild ⇒ a DM; a fetch would be needed to distinguish guild threads (deferred).
        kind = if (guildId == null) ChannelKind.DM else ChannelKind.GROUP,
        name = null,
    )
    return DiscordIncomingMessage(
        event = this,
        channel = channel,
        author = message.author.toAuthor(),
        isDirectedAtBot = directedAtBot(
            selfId = selfId,
            guildId = guildId,
            mentionedUserIds = message.mentionedUserIds,
            replyAuthorId = message.referencedMessage?.author?.id,
        ),
    )
}

/** DM, an @-mention of the bot, or a reply to one of the bot's own messages. */
internal fun directedAtBot(
    selfId: Snowflake,
    guildId: Snowflake?,
    mentionedUserIds: Set<Snowflake>,
    replyAuthorId: Snowflake?,
): Boolean = guildId == null || selfId in mentionedUserIds || replyAuthorId == selfId

/** Discord's per-server display name (global name) if set, else the account username. */
internal fun displayName(globalName: String?, username: String): String = globalName ?: username

private fun User?.toAuthor(): Author =
    if (this == null) Author("unknown") else Author(id.toString(), displayName(globalName, username), isBot)
