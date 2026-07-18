package kurier.telegram

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kurier.ChannelId
import kurier.ChannelKind
import kurier.Content
import kurier.MessageId
import kurier.PlatformId
import kurier.RichNode
import kurier.RichText
import kurier.StreamingMode
import kurier.StreamingOptions
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class TelegramChannelTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    // Records each call as method-name → request body, so tests can assert the call sequence.
    private fun recordingApi(calls: MutableList<Pair<String, String>>): TelegramApi =
        TelegramApi(
            "test",
            MockEngine { request ->
                val name = request.url.encodedPath.substringAfterLast('/')
                calls += name to (request.body as? TextContent)?.text.orEmpty()
                val result = if (name == "sendMessage" || name == "editMessageText") {
                    """{"message_id":1,"date":0,"chat":{"id":42,"type":"private"},"text":"x"}"""
                } else {
                    "true"
                }
                respond("""{"ok":true,"result":$result}""", HttpStatusCode.OK, jsonHeaders)
            },
        )

    private fun channel(api: TelegramApi): TelegramChannel = TelegramChannel(
        chatId = 42,
        api = api,
        id = ChannelId("telegram:42"),
        platform = PlatformId("telegram"),
        kind = ChannelKind.DM,
        name = null,
    )

    @Test
    fun `send delegates to sendMessage and returns a SentMessage with the returned id`() = runTest {
        val api = TelegramApi(
            "test",
            MockEngine {
                respond(
                    """{"ok":true,"result":{"message_id":7,"date":0,"chat":{"id":42,"type":"private"},"text":"hi"}}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val sent = channel(api).send(Content.text("hi"))

        assertEquals(MessageId("7"), sent.id)
        assertEquals(ChannelId("telegram:42"), sent.channelId)

        api.close()
    }

    @Test
    fun `send renders RichText to formatting entities`() = runTest {
        val calls = mutableListOf<Pair<String, String>>()
        val api = recordingApi(calls)

        channel(api).send(Content(RichText(listOf(RichNode.Bold(listOf(RichNode.Text("hi")))))))

        val (name, body) = calls.single()
        assertEquals("sendMessage", name)
        assertTrue(body.contains("\"text\":\"hi\""))
        assertTrue(body.contains("\"type\":\"bold\""))

        api.close()
    }

    @Test
    fun `streaming in BUFFERED mode drains the flow into one send`() = runTest {
        val calls = mutableListOf<Pair<String, String>>()
        val api = recordingApi(calls)

        channel(api).sendStreaming(flowOf("Hel", "lo"), StreamingOptions(mode = StreamingMode.BUFFERED))

        val (name, body) = calls.single()
        assertEquals("sendMessage", name)
        assertTrue(body.contains("\"text\":\"Hello\""))

        api.close()
    }

    @Test
    fun `streaming a synchronous flow sends once with no cursor`() = runTest {
        val calls = mutableListOf<Pair<String, String>>()
        val api = recordingApi(calls)

        // The whole flow arrives before the first send, so there is nothing to progressively edit.
        channel(api).sendStreaming(flowOf("Hel", "lo"), StreamingOptions(cursor = "▌"))

        val (name, body) = calls.single()
        assertEquals("sendMessage", name)
        assertTrue(body.contains("\"text\":\"Hello\""))
        assertFalse(body.contains("▌"))

        api.close()
    }

    @Test
    fun `streaming progressively edits, throttles, and the final edit strips the cursor`() = runTest {
        val calls = mutableListOf<Pair<String, String>>()
        val api = recordingApi(calls)

        val tokens = flow {
            emit("Hel")
            delay(2.seconds)
            emit("lo")
            delay(2.seconds)
            emit("!")
        }
        channel(api).sendStreaming(tokens, StreamingOptions(minEditInterval = 1.seconds, cursor = "▌"))

        // The first call sends the opening text with the streaming cursor; every later call is an edit
        // (the message is never re-sent), and the final edit lands the complete text with the cursor gone.
        assertEquals("sendMessage", calls.first().first)
        assertTrue(calls.first().second.contains("Hel▌"), "initial send should carry the cursor")
        assertTrue(calls.drop(1).all { it.first == "editMessageText" }, "every later call edits in place")
        assertTrue(calls.last().second.contains("\"text\":\"Hello!\""), "final edit lands the full text")
        assertFalse(calls.last().second.contains("▌"), "final edit strips the cursor")
        // Throttled: three tokens over two gaps collapse to a couple of edits, not one per token.
        assertTrue(calls.size in 2..3, "edits should be coalesced, was ${calls.size}")

        api.close()
    }

    @Test
    fun `indicateTyping sends a typing chat action`() = runTest {
        val calls = mutableListOf<Pair<String, String>>()
        val api = recordingApi(calls)

        channel(api).indicateTyping()

        val (name, body) = calls.single()
        assertEquals("sendChatAction", name)
        assertTrue(body.contains("\"action\":\"typing\""))

        api.close()
    }

    @Test
    fun `react sets an emoji reaction on the source message`() = runTest {
        val calls = mutableListOf<Pair<String, String>>()
        val api = recordingApi(calls)
        val bot = User(id = 999, isBot = true, username = "echobot")
        val message = Message(messageId = 7, chat = Chat(id = 42, type = "private"), date = 0, text = "hi")

        message.toIncomingMessage(PlatformId("telegram"), api, bot).react("👍")

        val (name, body) = calls.single()
        assertEquals("setMessageReaction", name)
        assertContains(body, "\"message_id\":7")
        assertContains(body, "\"emoji\":\"👍\"")

        api.close()
    }

    @Test
    fun `react swallows a platform rejection`() = runTest {
        // Telegram allows only a fixed reaction set — a rejected emoji must no-op, not throw.
        val api = TelegramApi(
            "test",
            MockEngine {
                respond("""{"ok":false,"error_code":400,"description":"REACTION_INVALID"}""", HttpStatusCode.OK, jsonHeaders)
            },
        )
        val bot = User(id = 999, isBot = true, username = "echobot")
        val message = Message(messageId = 7, chat = Chat(id = 42, type = "private"), date = 0, text = "hi")

        message.toIncomingMessage(PlatformId("telegram"), api, bot).react("🦖") // must not throw

        api.close()
    }
}
