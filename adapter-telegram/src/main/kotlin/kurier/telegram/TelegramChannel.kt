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
     * Reports what this adapter actually does through kurier today — every `true` is backed by a
     * real implementation. Platform features that exist but aren't wired yet report `false` and
     * flip to `true` (an additive, safe change) once they land.
     */
    override fun supports(capability: Capability): Boolean = when (capability) {
        Capability.EDITING,
        Capability.REACTIONS,
        Capability.TYPING,
        -> true

        // Provisional: Telegram has documents/photos (FILES), inline keyboards (BUTTONS), forum
        // topics (THREADS), and voice messages (VOICE); outbound wiring — and for buttons a core
        // API — lands post-0.1.0. Inbound attachments arrive regardless.
        Capability.FILES,
        Capability.BUTTONS,
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
