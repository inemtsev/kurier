package kurier.twitch

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kurier.ChannelId
import kurier.ChannelKind
import kurier.Content
import kurier.MessageId
import kurier.PlatformId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TwitchChannelTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun channel(api: TwitchApi): TwitchChannel = TwitchChannel(
        outbound = TwitchOutbound(api = api, broadcasterId = "100", senderId = "999"),
        id = ChannelId("twitch:100"),
        platform = PlatformId("twitch"),
        kind = ChannelKind.BROADCAST,
        name = null,
    )

    @Test
    fun `send posts to helix chat messages and returns the new message id`() = runTest {
        val calls = mutableListOf<Pair<HttpMethod, String>>()
        val api = TwitchApi(
            "client",
            "token",
            MockEngine { request ->
                calls += request.method to (request.body as? TextContent)?.text.orEmpty()
                respond("""{"data":[{"message_id":"msg-1","is_sent":true}]}""", HttpStatusCode.OK, jsonHeaders)
            },
        )

        val sent = channel(api).send(Content.text("hi chat"))

        assertEquals(MessageId("msg-1"), sent.id)
        assertEquals(ChannelId("twitch:100"), sent.channelId)
        val (method, body) = calls.single()
        assertEquals(HttpMethod.Post, method)
        assertContains(body, "\"broadcaster_id\":\"100\"")
        assertContains(body, "\"sender_id\":\"999\"")
        assertContains(body, "\"message\":\"hi chat\"")
    }

    @Test
    fun `send surfaces a message dropped by Twitch as a failure`() = runTest {
        val api = TwitchApi(
            "client",
            "token",
            // Twitch acks a dropped message (e.g. AutoMod) with HTTP 200 + is_sent=false.
            MockEngine {
                respond(
                    """{"data":[{"message_id":"x","is_sent":false,"drop_reason":{"code":"automod","message":"held for review"}}]}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val failure = assertFailsWith<TwitchApiException> { channel(api).send(Content.text("blocked")) }
        assertContains(failure.message.orEmpty(), "held for review")
    }

    @Test
    fun `sendStreaming buffers the token flow into a single send since Twitch cannot edit`() = runTest {
        val bodies = mutableListOf<String>()
        val api = TwitchApi(
            "client",
            "token",
            MockEngine { request ->
                bodies += (request.body as? TextContent)?.text.orEmpty()
                respond("""{"data":[{"message_id":"msg-2","is_sent":true}]}""", HttpStatusCode.OK, jsonHeaders)
            },
        )

        val sent = channel(api).sendStreaming(flowOf("Hello, ", "stream", "!"))

        assertEquals(MessageId("msg-2"), sent.id)
        // One send, no edits — the whole stream is drained into a single message with no cursor.
        assertContains(bodies.single(), "\"message\":\"Hello, stream!\"")
        assertTrue(bodies.single().none { it == '▌' }, "buffered send must not carry the streaming cursor")
    }
}
