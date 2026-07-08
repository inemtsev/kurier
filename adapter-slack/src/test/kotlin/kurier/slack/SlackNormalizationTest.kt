package kurier.slack

import com.slack.api.Slack
import com.slack.api.SlackConfig
import com.slack.api.model.event.MessageEvent
import kurier.ChannelId
import kurier.ChannelKind
import kurier.MessageId
import kurier.MessageRef
import kurier.PlatformId
import kurier.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SlackNormalizationTest {

    private val bot = "U1"

    @Test
    fun `a DM is directed at the bot`() {
        assertTrue(directedAtBot(channelType = "im", text = "hi", parentUserId = null, botUserId = bot))
    }

    @Test
    fun `a channel message without a mention is not directed`() {
        assertFalse(directedAtBot(channelType = "channel", text = "morning all", parentUserId = null, botUserId = bot))
    }

    @Test
    fun `a channel mention of the bot is directed`() {
        assertTrue(directedAtBot(channelType = "channel", text = "<@U1> deploy", parentUserId = null, botUserId = bot))
    }

    @Test
    fun `the legacy mention form is directed`() {
        assertTrue(directedAtBot(channelType = "channel", text = "<@U1|kurier> deploy", parentUserId = null, botUserId = bot))
    }

    @Test
    fun `a mention of someone else is not directed`() {
        assertFalse(directedAtBot(channelType = "channel", text = "<@U12> hi", parentUserId = null, botUserId = bot))
    }

    @Test
    fun `a threaded reply to the bot is directed`() {
        assertTrue(directedAtBot(channelType = "channel", text = "sounds good", parentUserId = bot, botUserId = bot))
    }

    @Test
    fun `a null text is handled`() {
        assertFalse(directedAtBot(channelType = "channel", text = null, parentUserId = null, botUserId = bot))
    }

    @Test
    fun `only im maps to a DM`() {
        assertEquals(ChannelKind.DM, channelKind("im"))
        assertEquals(ChannelKind.GROUP, channelKind("channel"))
        assertEquals(ChannelKind.GROUP, channelKind("group"))
        assertEquals(ChannelKind.GROUP, channelKind("mpim"))
        assertEquals(ChannelKind.GROUP, channelKind(null))
    }

    @Test
    fun `the bot's own posts are recognized by user id or bot id`() {
        assertTrue(event { user = "U1" }.isFrom(selfUserId = "U1", selfBotId = "B1"))
        assertTrue(event { botId = "B1" }.isFrom(selfUserId = "U1", selfBotId = "B1"))
        assertFalse(event { user = "U2" }.isFrom(selfUserId = "U1", selfBotId = "B1"))
        assertFalse(event { botId = "B2" }.isFrom(selfUserId = "U1", selfBotId = "B1"))
        assertFalse(event { user = "U2" }.isFrom(selfUserId = "U1", selfBotId = null))
    }

    @Test
    fun `a message event maps onto the kurier model`() {
        val source = event {
            channel = "C123"
            user = "U200"
            text = "hi there"
            ts = "1700000000.000100"
            threadTs = "1700000000.000001"
            channelType = "channel"
        }

        val message = source.toIncomingMessage(methods(), PlatformId("slack"), botUserId = bot)

        assertEquals(MessageId("1700000000.000100"), message.id)
        assertEquals(ChannelId("slack:C123"), message.channel.id)
        assertEquals(ChannelKind.GROUP, message.channel.kind)
        assertEquals("U200", message.author.id)
        assertFalse(message.author.isBot)
        assertEquals("hi there", message.text)
        assertEquals(MessageRef(ChannelId("slack:C123"), MessageId("1700000000.000001")), message.replyTo)
        assertFalse(message.isDirectedAtBot)
        assertSame(source, message.raw)
    }

    @Test
    fun `shared files map to attachments`() {
        val message = event {
            channel = "C1"
            user = "U200"
            text = "the log"
            ts = "1.5"
            channelType = "channel"
            files = listOf(
                com.slack.api.model.File().apply {
                    id = "F1"
                    name = "boot.log"
                    mimetype = "text/plain"
                    urlPrivate = "https://files.slack/x"
                },
            )
        }.toIncomingMessage(methods(), PlatformId("slack"), botUserId = bot)

        val attachment = message.attachments.single()
        assertEquals("boot.log", attachment.fileName)
        assertEquals("text/plain", attachment.contentType)
        assertEquals("https://files.slack/x", attachment.url)
        assertEquals("F1", attachment.id)
    }

    @Test
    fun `a top-level message has no reply reference`() {
        val message = event {
            channel = "D9"
            user = "U200"
            text = "hello"
            ts = "1.2"
            channelType = "im"
        }.toIncomingMessage(methods(), PlatformId("slack"), botUserId = bot)

        assertNull(message.replyTo)
        assertEquals(ChannelKind.DM, message.channel.kind)
        assertTrue(message.isDirectedAtBot)
    }

    private fun event(build: MessageEvent.() -> Unit): MessageEvent = MessageEvent().apply(build)

    // Building the client is network-free; the sender only touches HTTP when a send happens.
    private fun methods() = Slack.getInstance(SlackConfig()).methods("xoxb-test")
}
