package kurier.telegram

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kurier.ChannelId
import kurier.ChannelKind
import kurier.MessageId
import kurier.MessageRef
import kurier.PlatformId
import kurier.RichNode
import kurier.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TelegramNormalizationTest {

    private val platform = PlatformId("telegram")

    // Normalization never sends, so the engine is inert — the channel just needs an api to hold.
    private val api = TelegramApi("test", MockEngine { respondOk() })
    private val bot = User(id = 999, isBot = true, firstName = "Echo", username = "echobot")

    @Test
    fun `maps a private message to a directed DM and keeps the source on raw`() {
        val message = Message(
            messageId = 7,
            from = User(id = 111, isBot = false, firstName = "Ada", username = "ada"),
            chat = Chat(id = -100, type = "private"),
            date = 0,
            text = "hi",
        )

        val incoming = message.toIncomingMessage(platform, api, bot)

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

        val incoming = message.toIncomingMessage(platform, api, bot)

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

        val incoming = message.toIncomingMessage(platform, api, bot)

        assertEquals(MessageRef(ChannelId("telegram:-100"), MessageId("5")), incoming.replyTo)
    }

    @Test
    fun `falls back to an unknown author for channel posts`() {
        val message = Message(messageId = 9, chat = Chat(id = 50, type = "channel"), date = 0, text = "post")

        val incoming = message.toIncomingMessage(platform, api, bot)

        assertEquals("unknown", incoming.author.id)
        assertEquals(ChannelKind.BROADCAST, incoming.channel.kind)
    }

    @Test
    fun `treats a group reply to the bot as directed`() {
        val botMessage = Message(messageId = 1, from = bot, chat = Chat(id = -10, type = "supergroup"), date = 0, text = "ping me")
        val reply = Message(
            messageId = 2,
            from = User(id = 5, firstName = "Carol"),
            chat = Chat(id = -10, type = "supergroup"),
            date = 0,
            text = "pong",
            replyToMessage = botMessage,
        )

        assertTrue(reply.toIncomingMessage(platform, api, bot).isDirectedAtBot)
    }

    @Test
    fun `a group reply to another user is not directed`() {
        val otherMessage =
            Message(
                messageId = 1,
                from = User(id = 5, firstName = "Carol"),
                chat = Chat(id = -10, type = "supergroup"),
                date = 0,
                text = "hi",
            )
        val reply = Message(
            messageId = 2,
            from = User(id = 6, firstName = "Dave"),
            chat = Chat(id = -10, type = "supergroup"),
            date = 0,
            text = "yo",
            replyToMessage = otherMessage,
        )

        assertFalse(reply.toIncomingMessage(platform, api, bot).isDirectedAtBot)
    }

    @Test
    fun `parses bold and a text link into RichText nodes`() {
        val message = Message(
            messageId = 1,
            chat = Chat(id = 1, type = "private"),
            date = 0,
            text = "hi bold and link",
            entities = listOf(
                MessageEntity(type = "bold", offset = 3, length = 4),
                MessageEntity(type = "text_link", offset = 12, length = 4, url = "https://x.com"),
            ),
        )

        val nodes = message.toIncomingMessage(platform, api, bot).rich.nodes

        assertEquals(
            listOf(
                RichNode.Text("hi "),
                RichNode.Bold(listOf(RichNode.Text("bold"))),
                RichNode.Text(" and "),
                RichNode.Link("https://x.com", "link"),
            ),
            nodes,
        )
    }

    @Test
    fun `parses inline code and a code block with language`() {
        val message = Message(
            messageId = 1,
            chat = Chat(id = 1, type = "private"),
            date = 0,
            text = "see x and block",
            entities = listOf(
                MessageEntity(type = "code", offset = 4, length = 1),
                MessageEntity(type = "pre", offset = 10, length = 5, language = "kotlin"),
            ),
        )

        val nodes = message.toIncomingMessage(platform, api, bot).rich.nodes

        assertEquals(
            listOf(
                RichNode.Text("see "),
                RichNode.Code("x"),
                RichNode.Text(" and "),
                RichNode.CodeBlock("block", "kotlin"),
            ),
            nodes,
        )
    }

    @Test
    fun `keeps text for unsupported entity types`() {
        val message = Message(
            messageId = 1,
            chat = Chat(id = 1, type = "private"),
            date = 0,
            text = "spoiler here",
            entities = listOf(MessageEntity(type = "spoiler", offset = 0, length = 7)),
        )

        val incoming = message.toIncomingMessage(platform, api, bot)

        assertEquals("spoiler here", incoming.text)
        assertEquals(listOf(RichNode.Text("spoiler"), RichNode.Text(" here")), incoming.rich.nodes)
    }

    @Test
    fun `treats a group @-mention of the bot as directed`() {
        val message = Message(
            messageId = 1,
            from = User(id = 5, firstName = "Carol"),
            chat = Chat(id = -10, type = "supergroup"),
            date = 0,
            text = "@echobot help",
            entities = listOf(MessageEntity(type = "mention", offset = 0, length = 8)),
        )

        assertTrue(message.toIncomingMessage(platform, api, bot).isDirectedAtBot)
    }

    @Test
    fun `a group @-mention of another user is not directed`() {
        val message = Message(
            messageId = 1,
            from = User(id = 5, firstName = "Carol"),
            chat = Chat(id = -10, type = "supergroup"),
            date = 0,
            text = "@someoneelse hi",
            entities = listOf(MessageEntity(type = "mention", offset = 0, length = 12)),
        )

        assertFalse(message.toIncomingMessage(platform, api, bot).isDirectedAtBot)
    }

    @Test
    fun `treats a text_mention of the bot as directed`() {
        val message = Message(
            messageId = 1,
            from = User(id = 5, firstName = "Carol"),
            chat = Chat(id = -10, type = "supergroup"),
            date = 0,
            text = "Echo please",
            entities = listOf(MessageEntity(type = "text_mention", offset = 0, length = 4, user = bot)),
        )

        assertTrue(message.toIncomingMessage(platform, api, bot).isDirectedAtBot)
    }

    @Test
    fun `combines first and last name for the display name`() {
        val message = Message(
            messageId = 1,
            from = User(id = 1, firstName = "Ada", lastName = "Lovelace"),
            chat = Chat(id = 1, type = "private"),
            date = 0,
            text = "hi",
        )

        assertEquals("Ada Lovelace", message.toIncomingMessage(platform, api, bot).author.displayName)
    }

    @Test
    fun `attributes a channel post to its sender_chat`() {
        val message = Message(
            messageId = 1,
            senderChat = Chat(id = -100, type = "channel", title = "News"),
            chat = Chat(id = -100, type = "channel"),
            date = 0,
            text = "announcement",
        )

        val author = message.toIncomingMessage(platform, api, bot).author

        assertEquals("-100", author.id)
        assertEquals("News", author.displayName)
    }

    @Test
    fun `maps a document to an attachment`() {
        val message = Message(
            messageId = 1,
            chat = Chat(id = 1, type = "private"),
            date = 0,
            document = Document(fileId = "doc-1", fileName = "report.pdf", mimeType = "application/pdf"),
        )

        val attachment = message.toIncomingMessage(platform, api, bot).attachments.single()

        assertEquals("report.pdf", attachment.fileName)
        assertEquals("application/pdf", attachment.contentType)
        assertEquals("doc-1", attachment.id)
    }

    @Test
    fun `maps a photo to an attachment using the largest size`() {
        val message = Message(
            messageId = 1,
            chat = Chat(id = 1, type = "private"),
            date = 0,
            photo = listOf(PhotoSize(fileId = "small"), PhotoSize(fileId = "large")),
        )

        val attachment = message.toIncomingMessage(platform, api, bot).attachments.single()

        assertEquals("large", attachment.id)
        assertEquals("image/jpeg", attachment.contentType)
    }
}
