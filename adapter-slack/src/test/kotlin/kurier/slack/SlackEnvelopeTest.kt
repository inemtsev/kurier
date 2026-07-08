package kurier.slack

import com.google.gson.JsonParser
import com.slack.api.util.json.GsonFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SlackEnvelopeTest {

    private val gson = GsonFactory.createSnakeCase()

    private fun parse(payload: String): SlackEventAction = parseEnvelope(gson, JsonParser.parseString(payload))

    @Test
    fun `a plain message event carries the message fields`() {
        val payload =
            """
            {"type":"event_callback",
             "event":{"type":"message","channel":"C123","user":"U200","text":"hi there","ts":"1700000000.000100",
               "thread_ts":"1700000000.000001","channel_type":"channel"}}
            """.trimIndent()

        val action = assertIs<SlackEventAction.Message>(parse(payload))
        assertEquals("C123", action.event.channel)
        assertEquals("U200", action.event.user)
        assertEquals("hi there", action.event.text)
        assertEquals("1700000000.000100", action.event.ts)
        assertEquals("1700000000.000001", action.event.threadTs)
        assertEquals("channel", action.event.channelType)
    }

    @Test
    fun `a message_deleted subtype maps to Deleted`() {
        val payload =
            """
            {"type":"event_callback",
             "event":{"type":"message","subtype":"message_deleted","channel":"C123",
               "deleted_ts":"1700000000.000100","ts":"1700000000.000200","channel_type":"channel"}}
            """.trimIndent()

        val action = assertIs<SlackEventAction.Deleted>(parse(payload))
        assertEquals("C123", action.event.channel)
        assertEquals("1700000000.000100", action.event.deletedTs)
    }

    @Test
    fun `a file_share message flows as a message with its files`() {
        val payload =
            """
            {"type":"event_callback",
             "event":{"type":"message","subtype":"file_share","channel":"C123","user":"U200","text":"the log",
               "ts":"1700000000.000150","channel_type":"channel",
               "files":[{"id":"F1","name":"boot.log","mimetype":"text/plain","url_private":"https://files.slack/x"}]}}
            """.trimIndent()

        val action = assertIs<SlackEventAction.Message>(parse(payload))
        assertEquals("the log", action.event.text)
        assertEquals("boot.log", action.event.files.single().name)
    }

    @Test
    fun `a thread_broadcast reply flows as a message`() {
        val payload =
            """
            {"type":"event_callback",
             "event":{"type":"message","subtype":"thread_broadcast","channel":"C123","user":"U200",
               "text":"also in channel","ts":"1700000000.000160","thread_ts":"1700000000.000001"}}
            """.trimIndent()

        val action = assertIs<SlackEventAction.Message>(parse(payload))
        assertEquals("also in channel", action.event.text)
        assertEquals("1700000000.000001", action.event.threadTs)
    }

    @Test
    fun `a message_changed subtype is ignored`() {
        // Our own streaming edits come back as message_changed; acting on them would echo every edit.
        val payload =
            """
            {"type":"event_callback",
             "event":{"type":"message","subtype":"message_changed","channel":"C123","ts":"1700000000.000300"}}
            """.trimIndent()

        assertEquals(SlackEventAction.Ignore, parse(payload))
    }

    @Test
    fun `a bot_message subtype is ignored`() {
        val payload =
            """
            {"type":"event_callback",
             "event":{"type":"message","subtype":"bot_message","channel":"C123","ts":"1700000000.000400","bot_id":"B9"}}
            """.trimIndent()

        assertEquals(SlackEventAction.Ignore, parse(payload))
    }

    @Test
    fun `an app_mention is ignored to avoid double delivery`() {
        // The recommended app config subscribes to message_* and app_mention; the message event is canonical.
        val payload =
            """
            {"type":"event_callback",
             "event":{"type":"app_mention","channel":"C123","user":"U200","text":"<@U1> hi","ts":"1700000000.000500"}}
            """.trimIndent()

        assertEquals(SlackEventAction.Ignore, parse(payload))
    }

    @Test
    fun `reaction_added carries the reaction and its target`() {
        val payload =
            """
            {"type":"event_callback",
             "event":{"type":"reaction_added","user":"U200","reaction":"thumbsup","item_user":"U1",
               "item":{"type":"message","channel":"C123","ts":"1700000000.000100"},"event_ts":"1700000000.000600"}}
            """.trimIndent()

        val action = assertIs<SlackEventAction.ReactionAdded>(parse(payload))
        assertEquals("thumbsup", action.event.reaction)
        assertEquals("U200", action.event.user)
        assertEquals("C123", action.event.item.channel)
        assertEquals("1700000000.000100", action.event.item.ts)
    }

    @Test
    fun `reaction_removed maps to ReactionRemoved`() {
        val payload =
            """
            {"type":"event_callback",
             "event":{"type":"reaction_removed","user":"U200","reaction":"eyes",
               "item":{"type":"message","channel":"C123","ts":"1700000000.000100"},"event_ts":"1700000000.000700"}}
            """.trimIndent()

        val action = assertIs<SlackEventAction.ReactionRemoved>(parse(payload))
        assertEquals("eyes", action.event.reaction)
    }

    @Test
    fun `a payload without an event is ignored`() {
        assertEquals(SlackEventAction.Ignore, parse("""{"type":"event_callback"}"""))
        assertEquals(SlackEventAction.Ignore, parse("""{"type":"event_callback","event":"not an object"}"""))
        assertEquals(SlackEventAction.Ignore, parseEnvelope(gson, null))
    }

    @Test
    fun `an unknown event type is ignored`() {
        val payload = """{"type":"event_callback","event":{"type":"team_join","user":{"id":"U9"}}}"""

        assertEquals(SlackEventAction.Ignore, parse(payload))
    }

    @Test
    fun `the hello frame is recognized and other frames are not`() {
        assertTrue(isHelloFrame(gson, """{"type":"hello","num_connections":1}"""))
        assertFalse(isHelloFrame(gson, """{"type":"disconnect","reason":"refresh_requested"}"""))
        assertFalse(isHelloFrame(gson, "not json"))
    }
}
