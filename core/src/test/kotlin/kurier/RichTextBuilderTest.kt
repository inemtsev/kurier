package kurier

import kotlin.test.Test
import kotlin.test.assertEquals

class RichTextBuilderTest {

    @Test
    fun `builds a flat sequence of nodes`() {
        val text = richText {
            text("status: ")
            bold("OK")
            text(" ")
            code("exit=0")
        }

        assertEquals(
            listOf(
                RichNode.Text("status: "),
                RichNode.Bold(listOf(RichNode.Text("OK"))),
                RichNode.Text(" "),
                RichNode.Code("exit=0"),
            ),
            text.nodes,
        )
    }

    @Test
    fun `nests bold and italic via the block form`() {
        val text = richText {
            bold { italic("nested") }
        }

        assertEquals(
            listOf(RichNode.Bold(listOf(RichNode.Italic(listOf(RichNode.Text("nested")))))),
            text.nodes,
        )
    }

    @Test
    fun `code block carries an optional language and links carry an optional label`() {
        val text = richText {
            codeBlock("println()", "kotlin")
            link("https://x.com")
            link("https://x.com/build", "build")
        }

        assertEquals(
            listOf(
                RichNode.CodeBlock("println()", "kotlin"),
                RichNode.Link("https://x.com", null),
                RichNode.Link("https://x.com/build", "build"),
            ),
            text.nodes,
        )
    }

    @Test
    fun `plain-text projection flattens all nodes`() {
        val text = richText {
            text("see ")
            bold("the ")
            link("https://x.com", "build")
        }

        assertEquals("see the build", text.toPlainText())
    }

    @Test
    fun `Content rich wraps the DSL output`() {
        val content = Content.rich { bold("done") }

        assertEquals(richText { bold("done") }, content.rich)
        assertEquals("done", content.text)
    }
}
