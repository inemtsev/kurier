package kurier.telegram

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
}
