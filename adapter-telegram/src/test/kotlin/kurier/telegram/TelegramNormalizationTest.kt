package kurier.telegram

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

class TelegramNormalizationTest {

    private val platform = PlatformId("telegram")

    @Test
    fun `maps a private message to a directed DM and keeps the source on raw`() {
        val message = Message(
            messageId = 7,
            from = User(id = 111, isBot = false, firstName = "Ada", username = "ada"),
            chat = Chat(id = -100, type = "private"),
            date = 0,
            text = "hi",
        )

        val incoming = message.toIncomingMessage(platform)

        assertEquals(MessageId("7"), incoming.id)
        assertEquals("hi", incoming.text)
        assertEquals(ChannelId("telegram:-100"), incoming.channel.id)
        assertEquals(ChannelKind.DM, incoming.channel.kind)
        assertTrue(incoming.isDirectedAtBot)
        assertEquals("111", incoming.author.id)
        assertEquals("Ada", incoming.author.displayName)
        assertFalse(incoming.author.isBot)
        assertSame(message, incoming.raw)
        assertNull(incoming.replyTo)
    }

    @Test
    fun `maps a group message as not directed`() {
        val message = Message(
            messageId = 8,
            from = User(id = 1, firstName = "Bob"),
            chat = Chat(id = -200, type = "supergroup", title = "Eng"),
            date = 0,
            text = "hey",
        )

        val incoming = message.toIncomingMessage(platform)

        assertEquals(ChannelKind.GROUP, incoming.channel.kind)
        assertFalse(incoming.isDirectedAtBot)
        assertEquals("Eng", incoming.channel.name)
    }

    @Test
    fun `carries the reply reference`() {
        val parent = Message(messageId = 5, chat = Chat(id = -100, type = "private"), date = 0, text = "orig")
        val message = Message(
            messageId = 6,
            chat = Chat(id = -100, type = "private"),
            date = 0,
            text = "re",
            replyToMessage = parent,
        )

        val incoming = message.toIncomingMessage(platform)

        assertEquals(MessageRef(ChannelId("telegram:-100"), MessageId("5")), incoming.replyTo)
    }

    @Test
    fun `falls back to an unknown author for channel posts`() {
        val message = Message(messageId = 9, chat = Chat(id = 50, type = "channel"), date = 0, text = "post")

        val incoming = message.toIncomingMessage(platform)

        assertEquals("unknown", incoming.author.id)
        assertEquals(ChannelKind.BROADCAST, incoming.channel.kind)
    }
}
