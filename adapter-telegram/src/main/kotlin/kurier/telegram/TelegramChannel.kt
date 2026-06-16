package kurier.telegram

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kurier.Capability
import kurier.Channel
import kurier.ChannelId
import kurier.ChannelKind
import kurier.Content
import kurier.PlatformId
import kurier.SentMessage
import kurier.StreamingMode
import kurier.StreamingOptions
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

    /**
     * Streams [tokens] by progressively editing one message ([StreamingMode.EDIT]), or drains the
     * whole flow into a single send ([StreamingMode.BUFFERED]). Edits are throttled to at least
     * Telegram's [MIN_EDIT_INTERVAL]; a trailing edit always lands the complete text and strips the cursor.
     */
    override suspend fun sendStreaming(tokens: Flow<String>, options: StreamingOptions): SentMessage {
        val mode = if (supports(Capability.EDITING)) options.mode else StreamingMode.BUFFERED
        if (mode == StreamingMode.BUFFERED) {
            return send(Content.text(buildString { tokens.collect { append(it) } }))
        }
        return streamByEditing(tokens, options)
    }

    private suspend fun streamByEditing(tokens: Flow<String>, options: StreamingOptions): SentMessage = coroutineScope {
        val interval = maxOf(options.minEditInterval, MIN_EDIT_INTERVAL)
        val cursor = options.cursor.orEmpty()
        val stream = MutableStateFlow(StreamState())
        launch {
            val acc = StringBuilder()
            tokens.collect { token ->
                acc.append(token)
                stream.update { it.copy(text = acc.toString()) }
            }
            stream.update { it.copy(done = true) }
        }

        // Send the first message as soon as there is any text (or the stream ends), then edit on a throttle.
        val first = stream.first { it.text.isNotEmpty() || it.done }
        var displayed = first.text + if (first.done) "" else cursor
        val sent = send(Content.text(displayed))
        while (!stream.value.done) {
            delay(interval)
            if (stream.value.done) break
            val next = stream.value.text + cursor
            if (next != displayed) {
                sent.edit(Content.text(next))
                displayed = next
            }
        }
        val finalText = stream.value.text
        if (finalText != displayed) sent.edit(Content.text(finalText))
        sent
    }

    override suspend fun indicateTyping() {
        api.sendChatAction(chatId, "typing")
    }

    /** Cumulative streamed text plus whether the token flow has completed. */
    private data class StreamState(val text: String = "", val done: Boolean = false)

    private companion object {
        // Telegram throttles edits per chat; ~1/s is the safe sustained rate. Callers may ask for slower.
        val MIN_EDIT_INTERVAL: Duration = 1.seconds
    }
}
