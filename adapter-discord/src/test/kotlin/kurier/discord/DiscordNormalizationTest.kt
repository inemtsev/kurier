package kurier.discord

import dev.kord.common.entity.Snowflake
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscordNormalizationTest {

    private val bot = Snowflake(1uL)
    private val alice = Snowflake(2uL)
    private val guild = Snowflake(9uL)

    @Test
    fun `a DM is directed at the bot`() {
        assertTrue(directedAtBot(bot, guildId = null, mentionedUserIds = emptySet(), replyAuthorId = null))
    }

    @Test
    fun `a guild message without a mention is not directed`() {
        assertFalse(directedAtBot(bot, guildId = guild, mentionedUserIds = setOf(alice), replyAuthorId = null))
    }

    @Test
    fun `a guild @-mention of the bot is directed`() {
        assertTrue(directedAtBot(bot, guildId = guild, mentionedUserIds = setOf(alice, bot), replyAuthorId = null))
    }

    @Test
    fun `a reply to the bot is directed`() {
        assertTrue(directedAtBot(bot, guildId = guild, mentionedUserIds = emptySet(), replyAuthorId = bot))
    }

    @Test
    fun `a reply to another user is not directed`() {
        assertFalse(directedAtBot(bot, guildId = guild, mentionedUserIds = emptySet(), replyAuthorId = alice))
    }

    @Test
    fun `display name prefers the global name and falls back to username`() {
        assertEquals("Ada", displayName(globalName = "Ada", username = "ada123"))
        assertEquals("ada123", displayName(globalName = null, username = "ada123"))
    }
}
