package dev.kurier

/** Identifies a platform, e.g. `telegram`, `discord`, `slack`. */
@JvmInline
public value class PlatformId(public val value: String) {
    override fun toString(): String = value
}

/** Globally unique channel identifier, conventionally `<platform>:<native id>`. */
@JvmInline
public value class ChannelId(public val value: String) {
    override fun toString(): String = value
}

/** Platform-native message identifier. */
@JvmInline
public value class MessageId(public val value: String) {
    override fun toString(): String = value
}

public enum class ChannelKind { DM, GROUP, THREAD, BROADCAST }

/**
 * Capabilities differ across platforms. Check before using optional features;
 * unsupported operations degrade to no-ops rather than throwing.
 */
public enum class Capability { BUTTONS, THREADS, REACTIONS, EDITING, VOICE, FILES, TYPING }

public data class Author(
    public val id: String,
    public val displayName: String? = null,
    public val isBot: Boolean = false,
)

public data class MessageRef(public val channelId: ChannelId, public val messageId: MessageId)
