package kurier.twitch

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kurier.ChannelId
import kurier.ChannelKind
import kurier.MessageId
import kurier.PlatformId
import kurier.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TwitchNormalizationTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val platform = PlatformId("twitch")

    // Normalization never sends, so the channel's api is wired to an engine that is never called.
    private val api = TwitchApi("client", "token", MockEngine { respond("") })

    @Test
    fun `decodes a session_welcome envelope and extracts the session id`() {
        val frame =
            """
            {"metadata":{"message_id":"w","message_type":"session_welcome","message_timestamp":"t"},
             "payload":{"session":{"id":"SESSION123","status":"connected","keepalive_timeout_seconds":10,"reconnect_url":null}}}
            """.trimIndent()

        val message = json.decodeFromString<EventSubMessage>(frame)

        assertEquals("session_welcome", message.metadata.messageType)
        assertEquals("SESSION123", json.decodeFromJsonElement<WelcomePayload>(message.payload).session.id)
    }

    @Test
    fun `normalizes a chat notification into a broadcast IncomingMessage`() {
        val message = json.decodeFromString<EventSubMessage>(NOTIFICATION)
        assertEquals(TwitchApi.CHAT_MESSAGE_TYPE, message.metadata.subscriptionType)

        val event = json.decodeFromJsonElement<NotificationPayload>(message.payload).event
        val incoming = event.toIncomingMessage(api, platform, botId = "999")

        assertEquals(MessageId("abc"), incoming.id)
        assertEquals(ChannelId("twitch:100"), incoming.channel.id)
        assertEquals(ChannelKind.BROADCAST, incoming.channel.kind)
        assertEquals("200", incoming.author.id)
        assertEquals("Viewer", incoming.author.displayName)
        assertEquals("hi @bot", incoming.text)
        assertTrue(incoming.isDirectedAtBot) // the mention fragment targets bot id 999
    }

    @Test
    fun `directedness is exact via mention fragments`() {
        val mentioned = listOf(Fragment(type = "text"), Fragment(type = "mention", mention = Mention(userId = "999")))

        assertTrue(directedAtBot(mentioned, botId = "999"))
        assertFalse(directedAtBot(mentioned, botId = "888")) // a mention of someone else
        assertFalse(directedAtBot(listOf(Fragment(type = "text")), botId = "999")) // no mention
    }

    private companion object {
        val NOTIFICATION =
            """
            {"metadata":{"message_id":"n","message_type":"notification","message_timestamp":"t",
              "subscription_type":"channel.chat.message","subscription_version":"1"},
             "payload":{
               "subscription":{"id":"s","status":"enabled","type":"channel.chat.message"},
               "event":{
                 "broadcaster_user_id":"100","broadcaster_user_login":"streamer","broadcaster_user_name":"Streamer",
                 "chatter_user_id":"200","chatter_user_login":"viewer","chatter_user_name":"Viewer",
                 "message_id":"abc",
                 "message":{"text":"hi @bot","fragments":[
                   {"type":"text","text":"hi "},
                   {"type":"mention","text":"@bot","mention":{"user_id":"999","user_login":"bot","user_name":"Bot"}}
                 ]},
                 "color":"#FF0000","badges":[],"message_type":"text"
               }
             }}
            """.trimIndent()
    }
}
