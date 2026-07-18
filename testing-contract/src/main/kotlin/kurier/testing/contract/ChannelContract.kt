package kurier.testing.contract

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kurier.Capability
import kurier.Channel
import kurier.Content
import kurier.KurierException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Shared conformance suite for the [Channel] SPI. An adapter proves it honors the kurier contract by
 * subclassing this and supplying [newSubject]; the same invariants then run against every platform,
 * so cross-platform behavior (e.g. streaming degrading to a single send) can't silently drift.
 *
 * Every adapter conforms: each channel is built over a fake "sender" seam (standing in for its live
 * SDK/HTTP client), so these SPI invariants are exercised per platform without a network.
 */
public abstract class ChannelContract {

    /**
     * The channel under test paired with a way to read what it sent, since each platform records
     * outbound differently (an in-memory list, captured Ktor request bodies, …). [sentTexts] returns
     * the plain text of every message sent so far, in order.
     */
    public class Subject(
        public val channel: Channel,
        public val sentTexts: () -> List<String>,
    )

    /** Returns a fresh subject for each test — no state should leak between invariants. */
    public abstract fun newSubject(): Subject

    /**
     * A channel whose underlying platform call fails on [Channel.send] — proves the SPI error
     * contract: platform failures must surface as [KurierException], never as `internal` adapter
     * types or raw SDK exceptions. Return null (the default) only while the adapter has no failing
     * fake; the invariant is then skipped.
     */
    public open fun newFailingChannel(): Channel? = null

    @Test
    public fun `supports is total and deterministic`() {
        val channel = newSubject().channel
        Capability.entries.forEach { capability ->
            assertEquals(
                channel.supports(capability),
                channel.supports(capability),
                "supports($capability) must return a stable answer",
            )
        }
    }

    @Test
    public fun `send returns a SentMessage addressed to this channel`(): TestResult = runTest {
        val subject = newSubject()

        val sent = subject.channel.send(Content.text("hello"))

        assertEquals(subject.channel.id, sent.channelId, "SentMessage must carry the sending channel's id")
        assertTrue(sent.id.value.isNotBlank(), "SentMessage id must not be blank")
        assertTrue(subject.sentTexts().any { it.contains("hello") }, "the message text should reach the platform")
    }

    @Test
    public fun `sendStreaming ends with the full concatenated text`(): TestResult = runTest {
        val subject = newSubject()

        subject.channel.sendStreaming(flowOf("Hello, ", "stream", "!"))

        assertEquals("Hello, stream!", subject.sentTexts().last(), "the final message must be the whole stream")
    }

    @Test
    public fun `streaming without EDITING degrades to a single send`(): TestResult = runTest {
        val subject = newSubject()
        // Only meaningful for platforms that can't edit; editable ones are exercised by the test above.
        if (subject.channel.supports(Capability.EDITING)) return@runTest

        subject.channel.sendStreaming(flowOf("a", "b", "c"))

        assertEquals(listOf("abc"), subject.sentTexts(), "a non-editing channel must buffer into exactly one send")
    }

    @Test
    public fun `indicateTyping never throws`(): TestResult = runTest {
        // Optional features degrade to no-ops, never throw (Capability rule #6).
        newSubject().channel.indicateTyping()
    }

    @Test
    public fun `send failures surface as KurierException`(): TestResult = runTest {
        val channel = newFailingChannel() ?: return@runTest
        assertFailsWith<KurierException> { channel.send(Content.text("boom")) }
    }
}
