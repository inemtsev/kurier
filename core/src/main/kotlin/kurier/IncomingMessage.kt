package kurier

import kotlinx.coroutines.flow.Flow

public interface IncomingMessage {
    public val id: MessageId
    public val channel: Channel
    public val author: Author
    public val rich: RichText
    public val attachments: List<Attachment>
    public val replyTo: MessageRef?

    /** True for DMs, @-mentions of the bot, and replies to the bot's messages. */
    public val isDirectedAtBot: Boolean

    /**
     * Platform-specific underlying object (escape hatch). Prefer the typed
     * accessors shipped by adapter modules, e.g. `message.telegram`.
     */
    public val raw: Any?

    public suspend fun reply(content: Content): SentMessage = channel.send(content)

    /** Streaming reply — see [Channel.sendStreaming]. */
    public suspend fun reply(tokens: Flow<String>, options: StreamingOptions = StreamingOptions.Default): SentMessage =
        channel.sendStreaming(tokens, options)

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
