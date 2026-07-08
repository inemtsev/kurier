package kurier.slack

import com.slack.api.methods.MethodsClient
import com.slack.api.model.event.MessageEvent
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
 * Wraps a Slack `message` event as a kurier [IncomingMessage]. The SDK event stays on [raw] as the
 * escape hatch. Text is mapped as plain text — mrkdwn → RichText inbound parsing is deferred (like
 * Discord Markdown), so `<@U…>` mentions and `<url|label>` links appear raw. Display names need a
 * `users.info` lookup and are deferred too; [Author.id] carries the Slack user id.
 */
internal class SlackIncomingMessage(
    event: MessageEvent,
    override val channel: Channel,
    override val isDirectedAtBot: Boolean,
) : IncomingMessage {
    override val id: MessageId = MessageId(event.ts)
    override val author: Author = Author(id = event.user ?: event.botId ?: "unknown", isBot = event.botId != null)
    override val rich: RichText = RichText.plain(event.text.orEmpty())
    override val attachments: List<Attachment> = event.files.orEmpty().map {
        Attachment(fileName = it.name, contentType = it.mimetype, url = it.urlPrivate, id = it.id)
    }
    override val replyTo: MessageRef? = event.threadTs?.let { MessageRef(channel.id, MessageId(it)) }
    override val raw: Any = event

    override suspend fun react(emoji: String) {
        // Slack takes emoji shortcodes ("thumbsup"), not unicode — [emoji] is passed through verbatim.
        (channel as? SlackChannel)?.react(id, emoji)
    }
}

internal fun MessageEvent.toIncomingMessage(methods: MethodsClient, platform: PlatformId, botUserId: String): IncomingMessage {
    val slackChannel = SlackChannel(
        sender = MethodsSlackSender(methods, channel),
        id = ChannelId("${platform.value}:$channel"),
        platform = platform,
        kind = channelKind(channelType),
        name = null,
    )
    return SlackIncomingMessage(
        event = this,
        channel = slackChannel,
        isDirectedAtBot = directedAtBot(
            channelType = channelType,
            text = text,
            parentUserId = parentUserId,
            botUserId = botUserId,
        ),
    )
}

/** `im` is a DM; channels, private groups and multi-person IMs all behave as groups. */
internal fun channelKind(channelType: String?): ChannelKind =
    if (channelType == "im") ChannelKind.DM else ChannelKind.GROUP

/**
 * DM, an @-mention of the bot, or a threaded reply to one of the bot's messages. Slack mentions are
 * `<@U…>` (or the legacy `<@U…|name>` form) in the raw text — an exact user-id match, not a heuristic.
 */
internal fun directedAtBot(channelType: String?, text: String?, parentUserId: String?, botUserId: String): Boolean =
    channelType == "im" ||
        text.orEmpty().contains("<@$botUserId>") ||
        text.orEmpty().contains("<@$botUserId|") ||
        parentUserId == botUserId

/** True when the event is the bot's own post echoed back (by user id, or bot id for app posts). */
internal fun MessageEvent.isFrom(selfUserId: String?, selfBotId: String?): Boolean =
    (user != null && user == selfUserId) || (botId != null && botId == selfBotId)
