package kurier.telegram

import kotlinx.coroutines.flow.Flow
import kurier.Capability
import kurier.Channel
import kurier.ChannelId
import kurier.ChannelKind
import kurier.Content
import kurier.PlatformId
import kurier.SentMessage
import kurier.StreamingOptions
import kurier.sendStreamingByEditing
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A Telegram chat exposed as a kurier [Channel]. [send] renders RichText to the Bot API's
 * `entities`; [sendStreaming] progressively edits one message, throttled to Telegram's edit rate.
 */
internal class TelegramChannel(
    private val chatId: Long,
    private val api: TelegramApi,
    override val id: ChannelId,
    override val platform: PlatformId,
    override val kind: ChannelKind,
    override val name: String?,
) : Channel {

    /**
     * Reports what the Telegram platform can do — not what this adapter has wired yet.
     * Callers branch on this; unimplemented features degrade to no-ops rather than throw.
     * Contract: every `true` here must be backed by a real implementation by 0.1.0.
     */
    override fun supports(capability: Capability): Boolean = when (capability) {
        Capability.EDITING,
        Capability.REACTIONS,
        Capability.FILES,
        Capability.TYPING,
        Capability.BUTTONS,
        -> true

        // Provisional: Telegram does have forum topics (THREADS) and voice messages (VOICE);
        // revisit in M3 once each Capability's cross-platform meaning is defined.
        Capability.THREADS,
        Capability.VOICE,
        -> false
    }

    override suspend fun send(content: Content): SentMessage {
        val rendered = content.toTelegram()
        val sent = api.sendMessage(chatId, rendered.text, rendered.entities.ifEmpty { null })
        return TelegramSentMessage(api, chatId, sent.messageId, id)
    }

    /** Streams [tokens] via the shared edit-throttling engine, clamped to Telegram's [MIN_EDIT_INTERVAL]. */
    override suspend fun sendStreaming(tokens: Flow<String>, options: StreamingOptions): SentMessage =
        sendStreamingByEditing(tokens, options, MIN_EDIT_INTERVAL)

    override suspend fun indicateTyping() {
        api.sendChatAction(chatId, "typing")
    }

    private companion object {
        // Telegram throttles edits per chat; ~1/s is the safe sustained rate. Callers may ask for slower.
        val MIN_EDIT_INTERVAL: Duration = 1.seconds
    }
}
