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
    private val meOk = """{"ok":true,"result":{"id":42,"is_bot":true,"first_name":"Echo","username":"echobot"}}"""

    @Test
    fun `handshakes, reports Connected, and emits a normalized message`() = runTest {
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

        var pollCalls = 0
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/getMe")) {
                respond(meOk, HttpStatusCode.OK, jsonHeaders)
            } else {
                pollCalls++
                if (pollCalls == 1) respond(firstPoll, HttpStatusCode.OK, jsonHeaders) else awaitCancellation()
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
    fun `stops and reports Failed when the handshake token is rejected`() = runTest {
        // getMe is the first call; a 401 there means the token is dead — give up immediately.
        val engine = MockEngine {
            respond("""{"ok":false,"error_code":401,"description":"Unauthorized"}""", HttpStatusCode.OK, jsonHeaders)
        }
        val connection = TelegramConnection(
            api = TelegramApi("bad-token", engine),
            platform = PlatformId("telegram"),
            scope = backgroundScope,
        )

        val failed = assertIs<ConnectionState.Failed>(connection.state.first { it is ConnectionState.Failed })
        assertEquals(401, assertIs<TelegramApiException>(failed.cause).errorCode)

        connection.close()
    }

    @Test
    fun `backs off and retries on a transient handshake error`() = runTest {
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
        assertEquals(429, assertIs<TelegramApiException>(backoff.cause).errorCode)

        connection.close()
    }
}
