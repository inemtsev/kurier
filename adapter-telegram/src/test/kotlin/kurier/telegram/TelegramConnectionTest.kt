package kurier.telegram

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kurier.ConnectionState
import kurier.PlatformId
import kurier.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TelegramConnectionTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun `polls updates, reports Connected, and emits a normalized message`() = runTest {
        val firstPoll =
            """
            {"ok":true,"result":[
              {"update_id":1,"message":{
                 "message_id":7,"date":0,"text":"hi",
                 "from":{"id":111,"is_bot":false,"first_name":"Ada"},
                 "chat":{"id":-100,"type":"private"}
              }}
            ]}
            """.trimIndent()

        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) {
                respond(firstPoll, HttpStatusCode.OK, jsonHeaders)
            } else {
                awaitCancellation() // idle: park the loop instead of busy-polling
            }
        }
        val connection = TelegramConnection(
            api = TelegramApi("test", engine),
            platform = PlatformId("telegram"),
            scope = backgroundScope,
        )

        val message = connection.messages.first()

        assertEquals("hi", message.text)
        assertEquals(PlatformId("telegram"), message.channel.platform)
        assertEquals(ConnectionState.Connected, connection.state.value)

        connection.close()
        assertEquals(ConnectionState.Closed, connection.state.value)
    }

    @Test
    fun `stops and reports Failed on a fatal Bot API error`() = runTest {
        // 401 = invalid token; retrying never succeeds, so the loop must give up.
        val engine = MockEngine {
            respond("""{"ok":false,"error_code":401,"description":"Unauthorized"}""", HttpStatusCode.OK, jsonHeaders)
        }
        val connection = TelegramConnection(
            api = TelegramApi("bad-token", engine),
            platform = PlatformId("telegram"),
            scope = backgroundScope,
        )

        val failed = assertIs<ConnectionState.Failed>(connection.state.first { it is ConnectionState.Failed })
        val cause = assertIs<TelegramApiException>(failed.cause)
        assertEquals(401, cause.errorCode)

        connection.close()
    }

    @Test
    fun `backs off and retries on a transient Bot API error`() = runTest {
        // 429 = rate limited; transient, so the loop backs off and keeps the connection alive.
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) {
                respond("""{"ok":false,"error_code":429,"description":"Too Many Requests"}""", HttpStatusCode.OK, jsonHeaders)
            } else {
                awaitCancellation() // park after the first failure so Backoff is observable
            }
        }
        val connection = TelegramConnection(
            api = TelegramApi("test", engine),
            platform = PlatformId("telegram"),
            scope = backgroundScope,
        )

        val backoff = assertIs<ConnectionState.Backoff>(connection.state.first { it is ConnectionState.Backoff })
        val cause = assertIs<TelegramApiException>(backoff.cause)
        assertEquals(429, cause.errorCode)

        connection.close()
    }
}
