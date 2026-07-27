package kurier

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How [Channel.sendStreaming] presents the token stream to the user. */
public enum class StreamingMode {
    /** Progressively edit one message as tokens arrive — the "message types itself" effect. */
    EDIT,

    /**
     * Drain the whole flow, then send a single complete message. The shared engine keeps a
     * typing indicator alive while draining (a no-op where the platform has none).
     */
    BUFFERED,
}

/**
 * Options for [Channel.sendStreaming] — progressive message editing as tokens arrive.
 * Construct with named arguments; every parameter has a default.
 * Deliberately not a data class: options grow, and `copy`/`componentN` would freeze the parameter list.
 */
public class StreamingOptions(
    /** Presentation mode; [StreamingMode.EDIT] unless the platform forces [StreamingMode.BUFFERED]. */
    public val mode: StreamingMode = StreamingMode.EDIT,
    /** Lower bound on the interval between edits; the effective interval is the larger of this and the platform's minimum. */
    public val minEditInterval: Duration = 1.seconds,
    /** Appended while streaming is in progress; removed by the final edit. */
    public val cursor: String? = "▌",
    /**
     * The message the streamed reply links to, applied to the initial (or buffered) send —
     * same semantics and degradation as [Content.replyTo]. [IncomingMessage.reply] defaults it
     * to the message being replied to.
     */
    public val replyTo: MessageRef? = null,
) {
    public companion object {
        public val Default: StreamingOptions = StreamingOptions()
    }
}

/** This options instance with [replyTo] set — reconstruction, since options deliberately have no `copy`. */
public fun StreamingOptions.withReplyTo(replyTo: MessageRef?): StreamingOptions =
    StreamingOptions(mode, minEditInterval, cursor, replyTo)

/**
 * A conversation surface on a platform — DM, group, broadcast — that the bot can post into.
 *
 * Not `kotlinx.coroutines.channels.Channel`; the name is deliberate and frozen. Code using both
 * should alias the coroutines import: `import kotlinx.coroutines.channels.Channel as CoroutineChannel`.
 *
 * Conveniences with exactly one correct implementation (`send(String)`) live as extensions,
 * keeping the surface adapters and fakes must implement minimal.
 */
public interface Channel {
    public val id: ChannelId
    public val platform: PlatformId
    public val kind: ChannelKind
    public val name: String?

    /**
     * Whether this channel supports [capability] through kurier today. Implementations end their
     * `when` with `else -> false` so capabilities added in later releases degrade to unsupported
     * (never throw); consumers should likewise avoid exhaustive `when` over [Capability].
     */
    public fun supports(capability: Capability): Boolean

    /**
     * Sends [content], rendered to the platform's native rich-text dialect.
     * Platform failures surface as [KurierException]; cancellation passes through untouched.
     */
    public suspend fun send(content: Content): SentMessage

    /**
     * Sends a message that is progressively edited as [tokens] arrive — the
     * primary path for streaming LLM replies. With [StreamingMode.BUFFERED],
     * or on platforms without [Capability.EDITING], the flow is drained and
     * sent as a single message instead. Follows [send]'s error contract.
     *
     * If the token flow fails, or the call is cancelled after the first message was posted, the
     * partial message is finalized best-effort with the text received so far (cursor stripped)
     * and the original exception is rethrown **unwrapped** — catch your token source's own
     * exception types. Text beyond the platform's message-length limit is not chunked; the
     * failing send or edit propagates and the stream fails.
     */
    public suspend fun sendStreaming(tokens: Flow<String>, options: StreamingOptions = StreamingOptions.Default): SentMessage

    /** Shows a typing indicator where supported; no-op elsewhere. */
    public suspend fun indicateTyping() {
        // no-op by default; adapters override where the platform supports it
    }
}

public suspend fun Channel.send(text: String): SentMessage = send(Content.text(text))

/**
 * A handle to a message the bot sent. [edit] and [delete] follow [Channel.send]'s error contract:
 * platform failures surface as [KurierException], cancellation passes through untouched. Where the
 * platform lacks [Capability.EDITING] or message deletion, they degrade to no-ops instead.
 */
public interface SentMessage {
    public val id: MessageId
    public val channelId: ChannelId

    public suspend fun edit(content: Content)
    public suspend fun delete()
}
