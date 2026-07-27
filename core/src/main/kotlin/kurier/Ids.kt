package kurier

/** Identifies a platform, e.g. `telegram`, `discord`, `slack`. */
@JvmInline
public value class PlatformId(public val value: String) {
    override fun toString(): String = value
}

/**
 * Globally unique channel identifier. The format is a requirement, not a convention:
 * `<platform>:<native id>`, split at the **first** `:` — native ids may themselves contain `:`
 * (e.g. Matrix `!room:server.org`). Build with [of] and parse with [nativeId];
 * [AdapterConnection.channel] must return null for ids that don't carry its platform's prefix.
 */
@JvmInline
public value class ChannelId(public val value: String) {
    override fun toString(): String = value

    public companion object {
        /** Builds the canonical `<platform>:<native id>` form. */
        public fun of(platform: PlatformId, nativeId: String): ChannelId = ChannelId("${platform.value}:$nativeId")
    }
}

/**
 * The platform-native id of a channel belonging to [platform], or null when this id has no `:`
 * separator, carries another platform's prefix, or has a blank native part. Splits at the first `:`.
 */
public fun ChannelId.nativeId(platform: PlatformId): String? {
    val separator = value.indexOf(':')
    if (separator < 0 || value.substring(0, separator) != platform.value) return null
    return value.substring(separator + 1).takeIf { it.isNotBlank() }
}

/** Platform-native message identifier. */
@JvmInline
public value class MessageId(public val value: String) {
    override fun toString(): String = value
}

/**
 * Platform-native user identifier; unique only within a single platform. Key cross-platform
 * per-user state by ([Channel.platform], [UserId]) — unlike [ChannelId], this value is NOT
 * prefixed with the platform.
 */
@JvmInline
public value class UserId(public val value: String) {
    override fun toString(): String = value
}

/** Conversation shape. Grows additively in minor releases — avoid exhaustive `when` over it. */
public enum class ChannelKind { DM, GROUP, THREAD, BROADCAST }

/**
 * Capabilities differ across platforms. Check before using optional features;
 * unsupported operations degrade to no-ops rather than throwing. A `true` means the feature
 * works through kurier today, not merely that the platform has it.
 *
 * New values are added in minor releases: adapters implement [Channel.supports] with an
 * `else -> false` branch — never an exhaustive `when` — and consumers should likewise
 * avoid exhaustive matches over this enum.
 */
public enum class Capability {
    /** Interactive buttons/components on outgoing messages. */
    BUTTONS,

    /** Threads addressable as send targets. */
    THREADS,

    /** Emoji reactions ([IncomingMessage.react]). */
    REACTIONS,

    /** In-place message editing — the streaming-edit reply path ([Channel.sendStreaming]). */
    EDITING,

    /** Voice/audio messages. */
    VOICE,

    /** Outbound file attachments. */
    FILES,

    /** Typing indicators ([Channel.indicateTyping]). */
    TYPING,
}

public data class Author(
    public val id: UserId,
    public val displayName: String? = null,
    public val isBot: Boolean = false,
)

public data class MessageRef(public val channelId: ChannelId, public val messageId: MessageId)
