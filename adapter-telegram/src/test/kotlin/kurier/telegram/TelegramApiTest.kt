package kurier.telegram

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TelegramApiTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun `getUpdates parses the envelope, maps snake_case, and ignores unknown fields`() = runTest {
        val body =
            """
            {"ok":true,"unexpected":"ignored","result":[
              {"update_id":42,"message":{
                 "message_id":7,"date":1700000000,"text":"hi",
                 "from":{"id":111,"is_bot":false,"first_name":"Ada","username":"ada"},
                 "chat":{"id":-100,"type":"group","title":"Test Group"}
              }}
            ]}
            """.trimIndent()
        val api = TelegramApi("test", MockEngine { respond(body, HttpStatusCode.OK, jsonHeaders) })

        val update = api.getUpdates(offset = 0, timeoutSeconds = 0).single()

        assertEquals(42L, update.updateId)
        val message = assertNotNull(update.message)
        assertEquals(7L, message.messageId)
        assertEquals("hi", message.text)
        assertEquals(-100L, message.chat.id)
        val from = assertNotNull(message.from)
        assertEquals("Ada", from.firstName)
        assertEquals(false, from.isBot)

        api.close()
    }

    @Test
    fun `getUpdates sends offset and timeout as query params on the bot path`() = runTest {
        var captured: Url? = null
        val api = TelegramApi(
            "secret-token",
            MockEngine { request ->
                captured = request.url
                respond("""{"ok":true,"result":[]}""", HttpStatusCode.OK, jsonHeaders)
            },
        )

        api.getUpdates(offset = 5, timeoutSeconds = 30)

        val url = assertNotNull(captured)
        assertEquals("5", url.parameters["offset"])
        assertEquals("30", url.parameters["timeout"])
        assertTrue(url.encodedPath.endsWith("/getUpdates"))
        assertTrue(url.encodedPath.contains("secret-token"))

        api.close()
    }

    @Test
    fun `getUpdates throws TelegramApiException on an ok=false envelope`() = runTest {
        // Telegram returns the failure as a JSON body; our envelope check is status-agnostic.
        val api = TelegramApi(
            "test",
            MockEngine {
                respond("""{"ok":false,"error_code":401,"description":"Unauthorized"}""", HttpStatusCode.OK, jsonHeaders)
            },
        )

        val error = assertFailsWith<TelegramApiException> {
            api.getUpdates(offset = 0, timeoutSeconds = 0)
        }
        assertEquals(401, error.errorCode)

        api.close()
    }

    @Test
    fun `sendMessage posts the chat_id and text and returns the sent message`() = runTest {
        var method: HttpMethod? = null
        var body: String? = null
        val api = TelegramApi(
            "test",
            MockEngine { request ->
                method = request.method
                body = (request.body as TextContent).text
                respond(
                    """{"ok":true,"result":{"message_id":99,"date":0,"chat":{"id":42,"type":"private"},"text":"hello"}}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val sent = api.sendMessage(chatId = 42, text = "hello")

        assertEquals(HttpMethod.Post, method)
        val sentBody = assertNotNull(body)
        assertTrue(sentBody.contains("\"chat_id\":42"))
        assertTrue(sentBody.contains("\"text\":\"hello\""))
        assertEquals(99L, sent.messageId)

        api.close()
    }

    @Test
    fun `sendMessage throws on a 403, which is not connection-fatal`() = runTest {
        val api = TelegramApi(
            "test",
            MockEngine {
                respond(
                    """{"ok":false,"error_code":403,"description":"Forbidden: bot was blocked by the user"}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val error = assertFailsWith<TelegramApiException> { api.sendMessage(chatId = 42, text = "hi") }
        assertEquals(403, error.errorCode)
        assertFalse(error.isFatal) // per-recipient failure, never tears down the connection

        api.close()
    }

    @Test
    fun `sendMessage serializes formatting entities`() = runTest {
        var body: String? = null
        val api = TelegramApi(
            "test",
            MockEngine { request ->
                body = (request.body as TextContent).text
                respond(
                    """{"ok":true,"result":{"message_id":1,"date":0,"chat":{"id":42,"type":"private"},"text":"hi"}}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        api.sendMessage(chatId = 42, text = "hi", entities = listOf(MessageEntity(type = "bold", offset = 0, length = 2)))

        val sentBody = assertNotNull(body)
        assertTrue(sentBody.contains("\"type\":\"bold\""))
        assertTrue(sentBody.contains("\"offset\":0"))
        assertTrue(sentBody.contains("\"length\":2"))

        api.close()
    }

    @Test
    fun `editMessageText posts chat_id, message_id and text and returns the edited message`() = runTest {
        var method: HttpMethod? = null
        var body: String? = null
        val api = TelegramApi(
            "test",
            MockEngine { request ->
                method = request.method
                body = (request.body as TextContent).text
                respond(
                    """{"ok":true,"result":{"message_id":7,"date":0,"chat":{"id":42,"type":"private"},"text":"edited"}}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val edited = api.editMessageText(chatId = 42, messageId = 7, text = "edited")

        assertEquals(HttpMethod.Post, method)
        val sentBody = assertNotNull(body)
        assertTrue(sentBody.contains("\"chat_id\":42"))
        assertTrue(sentBody.contains("\"message_id\":7"))
        assertTrue(sentBody.contains("\"text\":\"edited\""))
        assertEquals(7L, edited.messageId)

        api.close()
    }

    @Test
    fun `deleteMessage posts chat_id and message_id`() = runTest {
        var body: String? = null
        val api = TelegramApi(
            "test",
            MockEngine { request ->
                body = (request.body as TextContent).text
                respond("""{"ok":true,"result":true}""", HttpStatusCode.OK, jsonHeaders)
            },
        )

        api.deleteMessage(chatId = 42, messageId = 7)

        val sentBody = assertNotNull(body)
        assertTrue(sentBody.contains("\"chat_id\":42"))
        assertTrue(sentBody.contains("\"message_id\":7"))

        api.close()
    }

    @Test
    fun `sendChatAction posts the action`() = runTest {
        var body: String? = null
        val api = TelegramApi(
            "test",
            MockEngine { request ->
                body = (request.body as TextContent).text
                respond("""{"ok":true,"result":true}""", HttpStatusCode.OK, jsonHeaders)
            },
        )

        api.sendChatAction(chatId = 42, action = "typing")

        val sentBody = assertNotNull(body)
        assertTrue(sentBody.contains("\"chat_id\":42"))
        assertTrue(sentBody.contains("\"action\":\"typing\""))

        api.close()
    }

    @Test
    fun `setMessageReaction posts an emoji reaction`() = runTest {
        var body: String? = null
        val api = TelegramApi(
            "test",
            MockEngine { request ->
                body = (request.body as TextContent).text
                respond("""{"ok":true,"result":true}""", HttpStatusCode.OK, jsonHeaders)
            },
        )

        api.setMessageReaction(chatId = 42, messageId = 7, emoji = "👍")

        val sentBody = assertNotNull(body)
        assertTrue(sentBody.contains("\"message_id\":7"))
        assertTrue(sentBody.contains("\"type\":\"emoji\""))
        assertTrue(sentBody.contains("\"emoji\":\"👍\""))

        api.close()
    }

    @Test
    fun `getMe returns the bot account`() = runTest {
        val api = TelegramApi(
            "test",
            MockEngine {
                respond(
                    """{"ok":true,"result":{"id":42,"is_bot":true,"first_name":"Echo","username":"echobot"}}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val me = api.getMe()

        assertEquals(42L, me.id)
        assertEquals("echobot", me.username)
        assertTrue(me.isBot)

        api.close()
    }
}
