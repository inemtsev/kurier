package kurier

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How [Channel.sendStreaming] presents the token stream to the user. */
public enum class StreamingMode {
    /** Progressively edit one message as tokens arrive — the "message types itself" effect. */
    EDIT,

    /**
     * Drain the whole flow, then send a single complete message. Adapters keep a
     * typing indicator alive while draining where the platform supports one.
     */
    BUFFERED,
}

/** Options for [Channel.sendStreaming] — progressive message editing as tokens arrive. */
public data class StreamingOptions(
    /** Presentation mode; [StreamingMode.EDIT] unless the platform forces [StreamingMode.BUFFERED]. */
    public val mode: StreamingMode = StreamingMode.EDIT,
    /** Minimum interval between edits; adapters clamp to platform rate limits. */
    public val minEditInterval: Duration = 1.seconds,
    /** Appended while streaming is in progress; removed by the final edit. */
    public val cursor: String? = "▌",
) {
    public companion object {
        public val Default: StreamingOptions = StreamingOptions()
    }
}

public interface Channel {
    public val id: ChannelId
    public val platform: PlatformId
    public val kind: ChannelKind
    public val name: String?

    public fun supports(capability: Capability): Boolean

    public suspend fun send(content: Content): SentMessage

    /**
     * Sends a message that is progressively edited as [tokens] arrive — the
     * primary path for streaming LLM replies. With [StreamingMode.BUFFERED],
     * or on platforms without [Capability.EDITING], the flow is drained and
     * sent as a single message instead.
     */
    public suspend fun sendStreaming(tokens: Flow<String>, options: StreamingOptions = StreamingOptions.Default): SentMessage

    /** Shows a typing indicator where supported; no-op elsewhere. */
    public suspend fun indicateTyping() {
        // no-op by default; adapters override where the platform supports it
    }
}

public suspend fun Channel.send(text: String): SentMessage = send(Content.text(text))

public interface SentMessage {
    public val id: MessageId
    public val channelId: ChannelId

    public suspend fun edit(content: Content)
    public suspend fun delete()
}
