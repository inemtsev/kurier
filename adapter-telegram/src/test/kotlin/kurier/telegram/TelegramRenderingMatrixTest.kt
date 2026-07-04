package kurier.telegram

import kurier.testing.assertRenderingMatrix
import kotlin.test.Test

/** Telegram renders text + offset-based entities; the golden pins offsets (UTF-16) and empty-span dropping. */
class TelegramRenderingMatrixTest {

    @Test
    fun `renders the matrix as text plus entities`() = assertRenderingMatrix(
        mapOf(
            "plain" to RenderedMessage("just text", emptyList()),
            "bold" to RenderedMessage("bold", listOf(MessageEntity("bold", 0, 4))),
            "italic" to RenderedMessage("italic", listOf(MessageEntity("italic", 0, 6))),
            "boldInItalic" to RenderedMessage("x", listOf(MessageEntity("bold", 0, 1), MessageEntity("italic", 0, 1))),
            "inlineCode" to RenderedMessage("code", listOf(MessageEntity("code", 0, 4))),
            "codeWithSpecials" to RenderedMessage("a`b < c & d", listOf(MessageEntity("code", 0, 11))),
            "codeBacktick" to RenderedMessage("a`", listOf(MessageEntity("code", 0, 2))),
            "codeBlock" to RenderedMessage("line 1\nline 2", listOf(MessageEntity("pre", 0, 13))),
            "codeBlockWithLang" to RenderedMessage("val x = 1", listOf(MessageEntity("pre", 0, 9, language = "kotlin"))),
            "linkWithLabel" to RenderedMessage("the site", listOf(MessageEntity("text_link", 0, 8, url = "https://x.com"))),
            "bareLink" to RenderedMessage("https://x.com", listOf(MessageEntity("text_link", 0, 13, url = "https://x.com"))),
            "emptyBold" to RenderedMessage("kept", emptyList()),
            "emoji" to RenderedMessage("🎉 hi", listOf(MessageEntity("bold", 3, 2))),
            "escaping" to RenderedMessage("a * b _ c | d < e > f & g", emptyList()),
            "everything" to RenderedMessage(
                "see b i c l",
                listOf(
                    MessageEntity("bold", 4, 1),
                    MessageEntity("italic", 6, 1),
                    MessageEntity("code", 8, 1),
                    MessageEntity("text_link", 10, 1, url = "https://x.com"),
                ),
            ),
        ),
    ) { it.toTelegram() }
}
