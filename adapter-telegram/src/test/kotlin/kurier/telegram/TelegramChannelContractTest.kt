package kurier.telegram

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
import kurier.Channel
import kurier.ChannelId
import kurier.ChannelKind
import kurier.PlatformId
import kurier.testing.contract.ChannelContract

/** Conformance of [TelegramChannel] (an editing channel that streams via in-place edits) to the [ChannelContract]. */
class TelegramChannelContractTest : ChannelContract() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun newSubject(): Subject {
        val bodies = mutableListOf<String>()
        val api = TelegramApi(
            "test",
            MockEngine { request ->
                bodies += (request.body as? TextContent)?.text.orEmpty()
                // sendMessage/editMessageText return a Message; other calls (e.g. sendChatAction) return true.
                val name = request.url.encodedPath.substringAfterLast('/')
                val result = if (name == "sendMessage" || name == "editMessageText") {
                    """{"message_id":1,"date":0,"chat":{"id":42,"type":"private"},"text":"x"}"""
                } else {
                    "true"
                }
                respond("""{"ok":true,"result":$result}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )
        val channel = TelegramChannel(
            chatId = 42,
            api = api,
            id = ChannelId("telegram:42"),
            platform = PlatformId("telegram"),
            kind = ChannelKind.DM,
            name = null,
        )
        return Subject(channel) {
            bodies.mapNotNull { json.parseToJsonElement(it).jsonObject["text"]?.jsonPrimitive?.contentOrNull }
        }
    }

    /** Exercises the real failure path: an `ok: false` envelope must surface as [kurier.KurierException]. */
    override fun newFailingChannel(): Channel {
        val api = TelegramApi(
            "test",
            MockEngine {
                respond(
                    """{"ok":false,"error_code":400,"description":"Bad Request: message text is empty"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        return TelegramChannel(
            chatId = 42,
            api = api,
            id = ChannelId("telegram:42"),
            platform = PlatformId("telegram"),
            kind = ChannelKind.DM,
            name = null,
        )
    }
}
