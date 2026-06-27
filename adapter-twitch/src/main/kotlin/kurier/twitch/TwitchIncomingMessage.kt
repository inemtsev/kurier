package kurier.twitch

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
 * A Twitch chat message normalized into kurier's [IncomingMessage]. Twitch chat is plain text (plus
 * emote codes), so [rich] is the raw text. The EventSub event stays on [raw]. Reply refs are deferred.
 */
internal class TwitchIncomingMessage(
    private val event: ChatMessageEvent,
    override val channel: Channel,
    override val isDirectedAtBot: Boolean,
) : IncomingMessage {
    override val id: MessageId = MessageId(event.messageId)
    override val author: Author = Author(event.chatterUserId, event.chatterUserName)
    override val rich: RichText = RichText.plain(event.message.text)
    override val attachments: List<Attachment> = emptyList()
    override val replyTo: MessageRef? = null
    override val raw: Any = event
}

internal fun ChatMessageEvent.toIncomingMessage(api: TwitchApi, platform: PlatformId, botId: String): IncomingMessage {
    val channel = TwitchChannel(
        // Replies are posted as the bot account ([botId]) into this broadcaster's chat.
        outbound = TwitchOutbound(api = api, broadcasterId = broadcasterUserId, senderId = botId),
        id = ChannelId("${platform.value}:$broadcasterUserId"),
        platform = platform,
        // A Twitch channel's chat is a public broadcast room, not a DM or group.
        kind = ChannelKind.BROADCAST,
        name = null,
    )
    return TwitchIncomingMessage(
        event = this,
        channel = channel,
        isDirectedAtBot = directedAtBot(message.fragments, botId),
    )
}

/**
 * Twitch gives structured mentions, so directedness is exact (unlike Matrix's heuristic): a message
 * is directed at the bot if it carries a `mention` fragment targeting the bot's user id.
 */
internal fun directedAtBot(fragments: List<Fragment>, botId: String): Boolean =
    fragments.any { it.type == "mention" && it.mention?.userId == botId }
