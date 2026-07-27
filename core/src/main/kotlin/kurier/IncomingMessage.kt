package kurier

import kotlinx.coroutines.flow.Flow

/**
 * A normalized inbound message. Members with default bodies ([reply], [react]) are platform
 * override points; conveniences with exactly one correct implementation ([text], `reply(String)`)
 * live as extensions, keeping the surface adapters and fakes must implement minimal.
 */
public interface IncomingMessage {
    public val id: MessageId
    public val channel: Channel
    public val author: Author
    public val rich: RichText
    public val attachments: List<Attachment>

    /**
     * The message this one replies to, when the platform reports one; null otherwise. On
     * thread-based platforms (Slack) this is the thread's **root** message — the finest reply
     * granularity the platform offers — not a specific mid-thread message. Adapters that do not
     * yet surface reply refs leave this null.
     */
    public val replyTo: MessageRef?

    /**
     * True for DMs, @-mentions of the bot, and replies to the bot's messages. Best-effort —
     * accuracy depends on what each platform exposes; see the adapter's documentation for its
     * exact rule.
     */
    public val isDirectedAtBot: Boolean

    /**
     * Platform-specific underlying object (escape hatch). Prefer the typed
     * accessors shipped by adapter modules, e.g. `message.telegram`.
     */
    public val raw: Any?

    /**
     * Replies to this message. When [Content.replyTo] is unset it defaults to this message's ref,
     * so adapters render native reply-linking where wired (Telegram `reply_to_message_id`, Discord
     * message reference, Slack thread — a threaded message's reply stays in its thread); platforms
     * without linking degrade to a plain channel send, never throw.
     */
    public suspend fun reply(content: Content): SentMessage =
        channel.send(if (content.replyTo != null) content else content.withReplyTo(MessageRef(channel.id, id)))

    /** Streaming reply — see [Channel.sendStreaming]. Reply-linking defaults as in [reply]. */
    public suspend fun reply(tokens: Flow<String>, options: StreamingOptions = StreamingOptions.Default): SentMessage =
        channel.sendStreaming(tokens, if (options.replyTo != null) options else options.withReplyTo(MessageRef(channel.id, id)))

    /**
     * Adds a reaction, best-effort. [emoji] is canonical **unicode** (`"👍"`); adapters translate
     * to platform-native forms where needed. Never throws into bot code: unsupported platforms
     * and platform-rejected emoji degrade to a no-op (cancellation excepted).
     */
    public suspend fun react(emoji: String) {
        // no-op by default; adapters override where the platform supports it
    }
}

/** Plain-text projection of [IncomingMessage.rich]. */
public val IncomingMessage.text: String get() = rich.toPlainText()

public suspend fun IncomingMessage.reply(text: String): SentMessage = reply(Content.text(text))
