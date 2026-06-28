package kurier.twitch

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TwitchFrameTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `session_welcome carries the session id and keepalive window`() {
        val frame =
            """
            {"metadata":{"message_id":"w","message_type":"session_welcome","message_timestamp":"t"},
             "payload":{"session":{"id":"S1","status":"connected","keepalive_timeout_seconds":10,"reconnect_url":null}}}
            """.trimIndent()

        val action = assertIs<FrameAction.Welcome>(parseFrame(json, frame))
        assertEquals("S1", action.sessionId)
        assertEquals(10, action.keepaliveSeconds)
    }

    @Test
    fun `session_reconnect maps to Reconnect`() {
        val frame =
            """
            {"metadata":{"message_id":"r","message_type":"session_reconnect","message_timestamp":"t"},
             "payload":{"session":{"id":"S1","status":"reconnecting","keepalive_timeout_seconds":null,
               "reconnect_url":"wss://eventsub.wss.twitch.tv/ws?reconnect=x"}}}
            """.trimIndent()

        assertEquals(FrameAction.Reconnect, parseFrame(json, frame))
    }

    @Test
    fun `revocation surfaces the subscription status`() {
        val frame =
            """
            {"metadata":{"message_id":"x","message_type":"revocation","message_timestamp":"t",
              "subscription_type":"channel.chat.message","subscription_version":"1"},
             "payload":{"subscription":{"id":"s","status":"authorization_revoked","type":"channel.chat.message"}}}
            """.trimIndent()

        val action = assertIs<FrameAction.Revoked>(parseFrame(json, frame))
        assertEquals("authorization_revoked", action.status)
    }

    @Test
    fun `session_keepalive is ignored`() {
        val frame =
            """{"metadata":{"message_id":"k","message_type":"session_keepalive","message_timestamp":"t"},"payload":{}}"""

        assertEquals(FrameAction.Ignore, parseFrame(json, frame))
    }

    @Test
    fun `a chat notification carries the event`() {
        val frame =
            """
            {"metadata":{"message_id":"n","message_type":"notification","message_timestamp":"t",
              "subscription_type":"channel.chat.message","subscription_version":"1"},
             "payload":{"subscription":{"id":"s","status":"enabled","type":"channel.chat.message"},
               "event":{"broadcaster_user_id":"100","chatter_user_id":"200","chatter_user_name":"Viewer",
                 "message_id":"abc","message":{"text":"hi","fragments":[]}}}}
            """.trimIndent()

        val action = assertIs<FrameAction.Notification>(parseFrame(json, frame))
        assertEquals("200", action.event.chatterUserId)
        assertEquals("abc", action.event.messageId)
    }

    @Test
    fun `a notification for another subscription type is ignored`() {
        val frame =
            """
            {"metadata":{"message_id":"n","message_type":"notification","message_timestamp":"t",
              "subscription_type":"channel.follow","subscription_version":"2"},
             "payload":{"subscription":{"id":"s","status":"enabled","type":"channel.follow"},"event":{}}}
            """.trimIndent()

        assertEquals(FrameAction.Ignore, parseFrame(json, frame))
    }
}
