package kurier

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * The standard [Channel.sendStreaming] strategy, reusable by any adapter: progressively edit one
 * message as [tokens] arrive ([StreamingMode.EDIT]), or — under [StreamingMode.BUFFERED], or when the
 * channel lacks [Capability.EDITING] — drain the flow into a single send.
 *
 * Adapters delegate here from their own override, passing the platform's safe [minEditInterval]
 * (e.g. Telegram ~1s, Discord ~5 edits/5s). Edits are throttled to at least that interval, and a
 * trailing edit always lands the complete text and strips the cursor. Built only on [Channel.send]
 * and [SentMessage.edit], so the token stream's pace is fully decoupled from the platform's edit rate.
 */
public suspend fun Channel.sendStreamingByEditing(
    tokens: Flow<String>,
    options: StreamingOptions,
    minEditInterval: Duration,
): SentMessage {
    val mode = if (supports(Capability.EDITING)) options.mode else StreamingMode.BUFFERED
    if (mode == StreamingMode.BUFFERED) {
        return send(Content.text(buildString { tokens.collect { append(it) } }))
    }
    return streamByEditing(tokens, options, maxOf(options.minEditInterval, minEditInterval))
}

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
