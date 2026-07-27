package kurier

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The standard [Channel.sendStreaming] strategy, reusable by any adapter: progressively edit one
 * message as [tokens] arrive ([StreamingMode.EDIT]), or — under [StreamingMode.BUFFERED], or when the
 * channel lacks [Capability.EDITING] — drain the flow into a single send, keeping a typing indicator
 * alive meanwhile.
 *
 * Adapters delegate here from their own override, passing the platform's safe [minEditInterval]
 * (e.g. Telegram ~1s, Discord ~5 edits/5s). Edits are throttled to at least that interval, and a
 * trailing edit lands the complete text and strips the cursor; platform errors (e.g. exceeding the
 * message-length limit) propagate. On mid-stream failure or cancellation the partial message is
 * finalized best-effort per [Channel.sendStreaming]'s contract. Built only on [Channel.send] and
 * [SentMessage.edit], so the token stream's pace is fully decoupled from the platform's edit rate.
 */
public suspend fun Channel.sendStreamingByEditing(
    tokens: Flow<String>,
    options: StreamingOptions,
    minEditInterval: Duration,
): SentMessage {
    val mode = if (supports(Capability.EDITING)) options.mode else StreamingMode.BUFFERED
    if (mode == StreamingMode.BUFFERED) {
        return coroutineScope {
            // Keeps "the bot is working on it" visible during a long drain (LLM generation);
            // indicateTyping is a no-op where unsupported, and its failures are cosmetic.
            val typing = launch {
                while (true) {
                    runCatching { indicateTyping() }
                    delay(TYPING_KEEPALIVE)
                }
            }
            try {
                send(Content(RichText.plain(buildString { tokens.collect { append(it) } }), replyTo = options.replyTo))
            } finally {
                typing.cancel()
            }
        }
    }
    return streamByEditing(tokens, options, maxOf(options.minEditInterval, minEditInterval))
}

// Re-triggered well inside every platform's chat-action lifetime (Telegram ~5s, Discord ~10s).
private val TYPING_KEEPALIVE: Duration = 4.seconds

@Suppress("TooGenericExceptionCaught") // finalize-then-rethrow must cover cancellation and every stream failure alike
private suspend fun Channel.streamByEditing(
    tokens: Flow<String>,
    options: StreamingOptions,
    interval: Duration,
): SentMessage = coroutineScope {
    // Cumulative streamed text plus whether the token flow has completed, in one signal so that
    // either a new token or completion re-triggers the waiter below (separate flows would deadlock
    // an empty stream).
    data class StreamState(val text: String = "", val done: Boolean = false)

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

    // Send the first message as soon as there is any text (or the stream ends), then edit on a
    // throttle. Only the initial send carries the reply link; edits address the sent message.
    val first = stream.first { it.text.isNotEmpty() || it.done }
    var displayed = first.text + if (first.done) "" else cursor
    val sent = send(Content(RichText.plain(displayed), replyTo = options.replyTo))
    try {
        while (!stream.value.done) {
            delay(interval)
            if (stream.value.done) break
            val next = stream.value.text + cursor
            if (next != displayed) {
                sent.edit(Content.text(next))
                displayed = next
            }
        }
    } catch (failure: Throwable) {
        // A dead token flow (or caller cancellation) must not leave the user staring at a frozen
        // cursor: finalize best-effort with the text received so far, then rethrow the original.
        // NonCancellable so this runs under cancellation; runCatching because the edit may fail for
        // the same reason the stream did, and that must not mask the original exception.
        withContext(NonCancellable) {
            val text = stream.value.text
            if (text.isNotEmpty() && text != displayed) runCatching { sent.edit(Content.text(text)) }
        }
        throw failure
    }
    // The success path propagates a failing trailing edit (e.g. message-length limit) unmasked.
    val finalText = stream.value.text
    if (finalText != displayed) sent.edit(Content.text(finalText))
    sent
}
