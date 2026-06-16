package kurier.discord

import kurier.RichNode
import kurier.RichText
import kotlin.test.Test
import kotlin.test.assertEquals

class DiscordRenderingTest {

    @Test
    fun `bold and italic render as Markdown delimiters`() {
        val text = RichText(
            listOf(
                RichNode.Text("hi "),
                RichNode.Bold(listOf(RichNode.Text("there"))),
                RichNode.Text(" "),
                RichNode.Italic(listOf(RichNode.Text("you"))),
            ),
        )

        assertEquals("hi **there** *you*", text.toDiscord())
    }

    @Test
    fun `nested bold-in-italic renders as triple asterisks`() {
        val text = RichText(listOf(RichNode.Bold(listOf(RichNode.Italic(listOf(RichNode.Text("x")))))))

        assertEquals("***x***", text.toDiscord())
    }

    @Test
    fun `plain text escapes formatting characters so it cannot inject formatting`() {
        val text = RichText.plain("a *b* _c_ ~d~ ||e|| `f` \\g")

        assertEquals("a \\*b\\* \\_c\\_ \\~d\\~ \\|\\|e\\|\\| \\`f\\` \\\\g", text.toDiscord())
    }

    @Test
    fun `inline code widens the fence when the content contains backticks`() {
        assertEquals("`plain`", RichText(listOf(RichNode.Code("plain"))).toDiscord())
        // A backtick inside ⇒ widen to a two-backtick fence.
        assertEquals("``a`b``", RichText(listOf(RichNode.Code("a`b"))).toDiscord())
        // Content ending in a backtick is padded so the closing fence stays distinct.
        assertEquals("`` a` ``", RichText(listOf(RichNode.Code("a`"))).toDiscord())
    }

    @Test
    fun `code block keeps the language and content literal`() {
        val text = RichText(listOf(RichNode.CodeBlock("val x = 1", "kotlin")))

        assertEquals("```kotlin\nval x = 1\n```", text.toDiscord())
    }

    @Test
    fun `a labeled link degrades to label and url since masked links do not render in messages`() {
        assertEquals(
            "build (https://ci/123)",
            RichText(listOf(RichNode.Link("https://ci/123", "build"))).toDiscord(),
        )
        // A bare or self-labeled url is emitted as-is for Discord to auto-link.
        assertEquals("https://x.com", RichText(listOf(RichNode.Link("https://x.com"))).toDiscord())
    }
}
