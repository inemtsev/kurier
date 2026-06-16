package kurier.telegram

import kurier.RichNode
import kurier.RichText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegramRenderingTest {

    @Test
    fun `plain text renders with no entities`() {
        val rendered = RichText.plain("just text").toTelegram()

        assertEquals("just text", rendered.text)
        assertTrue(rendered.entities.isEmpty())
    }

    @Test
    fun `bold and link render as offset-based entities`() {
        val rendered = RichText(
            listOf(
                RichNode.Text("hi "),
                RichNode.Bold(listOf(RichNode.Text("bold"))),
                RichNode.Text(" and "),
                RichNode.Link("https://x.com", "link"),
            ),
        ).toTelegram()

        assertEquals("hi bold and link", rendered.text)
        assertEquals(
            listOf(
                MessageEntity(type = "bold", offset = 3, length = 4),
                MessageEntity(type = "text_link", offset = 12, length = 4, url = "https://x.com"),
            ),
            rendered.entities,
        )
    }

    @Test
    fun `inline code and a code block with language render`() {
        val rendered = RichText(
            listOf(
                RichNode.Code("x"),
                RichNode.Text(" "),
                RichNode.CodeBlock("block", "kotlin"),
            ),
        ).toTelegram()

        assertEquals("x block", rendered.text)
        assertEquals(
            listOf(
                MessageEntity(type = "code", offset = 0, length = 1),
                MessageEntity(type = "pre", offset = 2, length = 5, language = "kotlin"),
            ),
            rendered.entities,
        )
    }

    @Test
    fun `a link without a label renders its url as the visible text`() {
        val rendered = RichText(listOf(RichNode.Link("https://x.com"))).toTelegram()

        assertEquals("https://x.com", rendered.text)
        assertEquals(
            listOf(MessageEntity(type = "text_link", offset = 0, length = 13, url = "https://x.com")),
            rendered.entities,
        )
    }

    @Test
    fun `empty spans are dropped so the Bot API never sees a zero-length entity`() {
        val rendered = RichText(
            listOf(
                RichNode.Bold(emptyList()),
                RichNode.Text("kept"),
            ),
        ).toTelegram()

        assertEquals("kept", rendered.text)
        assertTrue(rendered.entities.isEmpty())
    }

    @Test
    fun `offsets are UTF-16 units so spans after an emoji stay aligned`() {
        // "🎉" is a surrogate pair: two UTF-16 units, so the bold span starts at offset 3.
        val rendered = RichText(
            listOf(
                RichNode.Text("🎉 "),
                RichNode.Bold(listOf(RichNode.Text("hi"))),
            ),
        ).toTelegram()

        assertEquals("🎉 hi", rendered.text)
        assertEquals(listOf(MessageEntity(type = "bold", offset = 3, length = 2)), rendered.entities)
    }
}
