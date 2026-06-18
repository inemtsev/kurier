package kurier.discord

import dev.kord.common.entity.Snowflake
import kurier.Author
import kurier.ChannelEvent.ReactionAdded
import kurier.ChannelEvent.ReactionRemoved
import kurier.ChannelId
import kurier.MessageId
import kurier.PlatformId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DiscordEventTest {

    private val platform = PlatformId("discord")
    private val bot = Snowflake(1uL)
    private val alice = Snowflake(2uL)
    private val channel = Snowflake(7uL)
    private val message = Snowflake(8uL)

    @Test
    fun `a reaction by another user maps to the built event`() {
        val event = reactionEvent(platform, bot, ReactionInfo(alice, channel, message, "👍"), ::ReactionAdded)

        assertEquals(ReactionAdded(ChannelId("discord:7"), MessageId("8"), "👍", Author("2")), event)
    }

    @Test
    fun `the builder chooses the event type`() {
        val event = reactionEvent(platform, bot, ReactionInfo(alice, channel, message, "👎"), ::ReactionRemoved)

        assertEquals(ReactionRemoved(ChannelId("discord:7"), MessageId("8"), "👎", Author("2")), event)
    }

    @Test
    fun `the bot's own reaction is filtered out`() {
        assertNull(reactionEvent(platform, bot, ReactionInfo(bot, channel, message, "👍"), ::ReactionAdded))
    }
}
