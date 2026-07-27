package kurier

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class StreamingEditTest {

    // Records each outbound text as "send:<text>" / "edit:<text>" so tests can assert the call sequence.
    private class RecordingChannel(private val editing: Boolean) : Channel {
        val calls: MutableList<String> = mutableListOf()
        override val id: ChannelId = ChannelId("fake:1")
        override val platform: PlatformId = PlatformId("fake")
        override val kind: ChannelKind = ChannelKind.DM
        override val name: String? = null
        override fun supports(capability: Capability): Boolean = capability == Capability.EDITING && editing

        override suspend fun send(content: Content): SentMessage {
            calls += "send:${content.text}"
            return RecordingSentMessage(calls, id)
        }

        override suspend fun sendStreaming(tokens: Flow<String>, options: StreamingOptions): SentMessage =
            sendStreamingByEditing(tokens, options, 1.seconds)

        override suspend fun indicateTyping() {
            calls += "typing"
        }
    }

    private class RecordingSentMessage(
        private val calls: MutableList<String>,
        override val channelId: ChannelId,
    ) : SentMessage {
        override val id: MessageId = MessageId("1")
        override suspend fun edit(content: Content) {
            calls += "edit:${content.text}"
        }
        override suspend fun delete() = Unit
    }

    @Test
    fun `BUFFERED mode drains the flow into one send`() = runTest {
        val channel = RecordingChannel(editing = true)

        channel.sendStreamingByEditing(
            flowOf("Hel", "lo"),
            StreamingOptions(mode = StreamingMode.BUFFERED),
            minEditInterval = 1.seconds,
        )

        assertEquals(listOf("send:Hello"), channel.calls)
    }

    @Test
    fun `a channel without EDITING falls back to a single buffered send`() = runTest {
        val channel = RecordingChannel(editing = false)

        channel.sendStreamingByEditing(flowOf("Hel", "lo"), StreamingOptions(), minEditInterval = 1.seconds)

        assertEquals(listOf("send:Hello"), channel.calls)
    }

    @Test
    fun `a synchronous flow sends once with no cursor`() = runTest {
        val channel = RecordingChannel(editing = true)

        // The whole flow arrives before the first send, so there is nothing to progressively edit.
        channel.sendStreamingByEditing(flowOf("Hel", "lo"), StreamingOptions(cursor = "▌"), minEditInterval = 1.seconds)

        assertEquals(listOf("send:Hello"), channel.calls)
        assertTrue(channel.calls.none { it.contains("▌") })
    }

    @Test
    fun `a failing token flow finalizes the partial message and rethrows the original`() = runTest {
        val channel = RecordingChannel(editing = true)
        val tokens = flow {
            emit("Hel")
            delay(10.seconds)
            emit("lo")
            error("stream died")
        }

        val failure = assertFailsWith<IllegalStateException> {
            channel.sendStreamingByEditing(tokens, StreamingOptions(cursor = "▌"), minEditInterval = 1.seconds)
        }

        assertEquals("stream died", failure.message, "the original exception surfaces unwrapped")
        assertEquals("edit:Hello", channel.calls.last(), "the text received so far lands, cursor stripped")
    }

    @Test
    fun `cancelling mid-stream finalizes the partial message`() = runTest {
        val channel = RecordingChannel(editing = true)
        val tokens = flow {
            emit("Hel")
            delay(1.hours) // a stalled token source
        }
        val streaming = launch {
            channel.sendStreamingByEditing(tokens, StreamingOptions(cursor = "▌"), minEditInterval = 1.seconds)
        }

        delay(5.seconds) // let the first send land
        streaming.cancelAndJoin()

        assertEquals("send:Hel▌", channel.calls.first())
        assertEquals("edit:Hel", channel.calls.last(), "cancellation must not leave a frozen cursor")
    }

    @Test
    fun `BUFFERED keeps a typing indicator alive while draining`() = runTest {
        val channel = RecordingChannel(editing = false) // no EDITING forces the buffered path
        val tokens = flow {
            emit("Hel")
            delay(10.seconds) // a slow generation
            emit("lo")
        }

        channel.sendStreamingByEditing(tokens, StreamingOptions(), minEditInterval = 1.seconds)

        assertTrue(channel.calls.count { it == "typing" } >= 2, "keepalive should re-trigger during a 10s drain")
        assertEquals("send:Hello", channel.calls.last())
    }
}
