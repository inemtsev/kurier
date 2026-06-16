package kurier

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
}
