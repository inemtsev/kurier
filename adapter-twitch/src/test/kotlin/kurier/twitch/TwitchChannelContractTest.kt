package kurier.twitch

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kurier.ChannelId
import kurier.ChannelKind
import kurier.PlatformId
import kurier.testing.ChannelContract

/** Conformance of [TwitchChannel] (a non-editing channel that buffers streams) to the [ChannelContract]. */
class TwitchChannelContractTest : ChannelContract() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun newSubject(): Subject {
        val bodies = mutableListOf<String>()
        val api = TwitchApi(
            clientId = "cid",
            accessToken = "token",
            engine = MockEngine { request ->
                bodies += (request.body as? TextContent)?.text.orEmpty()
                respond(
                    """{"data":[{"message_id":"m","is_sent":true}]}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val channel = TwitchChannel(
            outbound = TwitchOutbound(api = api, broadcasterId = "100", senderId = "999"),
            id = ChannelId("twitch:100"),
            platform = PlatformId("twitch"),
            kind = ChannelKind.BROADCAST,
            name = null,
        )
        return Subject(channel) {
            bodies.mapNotNull { json.parseToJsonElement(it).jsonObject["message"]?.jsonPrimitive?.contentOrNull }
        }
    }
}
