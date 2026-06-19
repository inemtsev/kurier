package kurier.matrix

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MatrixNormalizationTest {

    private val bot = "@bot:example.org"

    @Test
    fun `a body naming the bot mxid is directed`() {
        assertTrue(directedAtBot(bot, body = "hey @bot:example.org help", formattedBody = null))
    }

    @Test
    fun `an html pill linking the bot mxid is directed`() {
        val pill = """<a href="https://matrix.to/#/@bot:example.org">Bot</a> help"""
        assertTrue(directedAtBot(bot, body = "Bot help", formattedBody = pill))
    }

    @Test
    fun `a message that does not name the bot is not directed`() {
        assertFalse(directedAtBot(bot, body = "hello everyone", formattedBody = "<b>hello</b> everyone"))
    }
}
