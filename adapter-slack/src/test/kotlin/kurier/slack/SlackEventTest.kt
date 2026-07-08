package kurier.slack

import com.slack.api.model.event.MessageDeletedEvent
import kurier.Author
import kurier.ChannelEvent.MessageDeleted
import kurier.ChannelEvent.ReactionAdded
import kurier.ChannelEvent.ReactionRemoved
import kurier.ChannelId
import kurier.MessageId
import kurier.PlatformId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SlackEventTest {

    private val platform = PlatformId("slack")
    private val bot = "U1"

    private fun info(user: String? = "U200") =
        SlackReactionInfo(userId = user, channelId = "C123", messageTs = "1700000000.000100", name = "thumbsup")

    @Test
    fun `a reaction from another user maps to ReactionAdded`() {
        assertEquals(
            ReactionAdded(ChannelId("slack:C123"), MessageId("1700000000.000100"), "thumbsup", Author("U200")),
            reactionEvent(platform, bot, info(), ::ReactionAdded),
        )
    }

    @Test
    fun `a reaction removal maps to ReactionRemoved`() {
        assertEquals(
            ReactionRemoved(ChannelId("slack:C123"), MessageId("1700000000.000100"), "thumbsup", Author("U200")),
            reactionEvent(platform, bot, info(), ::ReactionRemoved),
        )
    }

    @Test
    fun `the bot's own reaction is dropped`() {
        assertNull(reactionEvent(platform, bot, info(user = bot), ::ReactionAdded))
    }

    @Test
    fun `a reaction on a non-message target is dropped`() {
        // File reactions carry no channel/ts coordinates.
        val fileReaction = SlackReactionInfo(userId = "U200", channelId = null, messageTs = null, name = "eyes")

        assertNull(reactionEvent(platform, bot, fileReaction, ::ReactionAdded))
    }

    @Test
    fun `a deleted message maps to MessageDeleted`() {
        val event = MessageDeletedEvent().apply {
            channel = "C123"
            deletedTs = "1700000000.000100"
        }

        assertEquals(
            MessageDeleted(ChannelId("slack:C123"), MessageId("1700000000.000100")),
            deletedEvent(platform, event),
        )
    }

    @Test
    fun `a deletion without coordinates is dropped`() {
        assertNull(deletedEvent(platform, MessageDeletedEvent()))
    }
}
