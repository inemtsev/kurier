package kurier.slack

import kotlinx.coroutines.test.runTest
import kurier.ChannelId
import kurier.ChannelKind
import kurier.MessageId
import kurier.PlatformId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The unicode↔shortcode contract: canonical unicode in the API, shortcodes on the wire, no throws. */
class SlackEmojiTest {

    @Test
    fun `mapped unicode translates to its shortcode`() {
        assertEquals("thumbsup", emojiToSlackName("👍"))
        assertEquals("tada", emojiToSlackName("🎉"))
    }

    @Test
    fun `the variation selector does not affect the mapping`() {
        assertEquals("heart", emojiToSlackName("❤️"))
        assertEquals("heart", emojiToSlackName("❤"))
    }

    @Test
    fun `ascii input is already a shortcode and passes through`() {
        assertEquals("thumbsup", emojiToSlackName("thumbsup"))
        assertEquals("party-parrot", emojiToSlackName("party-parrot"))
    }

    @Test
    fun `unmapped unicode is unsendable`() {
        assertNull(emojiToSlackName("🦖"))
        assertNull(emojiToSlackName(""))
    }

    @Test
    fun `inbound shortcodes map to canonical unicode, unknown ones pass through`() {
        assertEquals("👍", slackNameToEmoji("thumbsup"))
        assertEquals("party-parrot", slackNameToEmoji("party-parrot"))
    }

    @Test
    fun `react translates and sends a mapped emoji`() = runTest {
        val sender = RecordingReactions()

        channel(sender).react(MessageId("1.2"), "👍")

        assertEquals(listOf("thumbsup"), sender.reacted)
    }

    @Test
    fun `react no-ops instead of sending an unmapped emoji`() = runTest {
        val sender = RecordingReactions()

        channel(sender).react(MessageId("1.2"), "🦖")

        assertEquals(emptyList(), sender.reacted)
    }

    @Test
    fun `react swallows a platform rejection`() = runTest {
        val sender = object : RecordingReactions() {
            override suspend fun react(messageId: MessageId, name: String): Unit =
                throw SlackApiCallException("reactions.add", "already_reacted")
        }

        channel(sender).react(MessageId("1.2"), "👍") // must not throw
    }

    private fun channel(sender: SlackSender) = SlackChannel(
        sender = sender,
        id = ChannelId("slack:C1"),
        platform = PlatformId("slack"),
        kind = ChannelKind.GROUP,
        name = null,
    )

    private open class RecordingReactions : SlackSender {
        val reacted = mutableListOf<String>()

        override suspend fun send(text: String): MessageId = MessageId("s-0")

        override suspend fun edit(messageId: MessageId, text: String) = Unit

        override suspend fun delete(messageId: MessageId) = Unit

        open override suspend fun react(messageId: MessageId, name: String) {
            reacted += name
        }
    }
}
