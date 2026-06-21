package kurier.matrix

import kurier.Content
import kurier.RichNode
import kurier.RichText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RenderedMatrixTest {

    @Test
    fun `pure plain text renders a body only, no html`() {
        val rendered = Content.text("just text").toMatrix()

        assertEquals("just text", rendered.body)
        assertNull(rendered.formattedBody)
    }

    @Test
    fun `bold and italic render to html with a plain-text body fallback`() {
        val content = Content(
            RichText(
                listOf(
                    RichNode.Text("hi "),
                    RichNode.Bold(listOf(RichNode.Text("there"))),
                    RichNode.Text(" "),
                    RichNode.Italic(listOf(RichNode.Text("you"))),
                ),
            ),
        )

        val rendered = content.toMatrix()

        assertEquals("hi there you", rendered.body)
        assertEquals("hi <strong>there</strong> <em>you</em>", rendered.formattedBody)
    }

    @Test
    fun `text is html-escaped uniformly, including inside inline code`() {
        val content = Content(
            RichText(listOf(RichNode.Text("a < b & c "), RichNode.Code("x < y"))),
        )

        val rendered = content.toMatrix()

        assertEquals("a < b & c x < y", rendered.body) // body keeps the raw characters
        assertEquals("a &lt; b &amp; c <code>x &lt; y</code>", rendered.formattedBody)
    }

    @Test
    fun `a code block carries the language class and escapes its content`() {
        val rendered = Content(RichText(listOf(RichNode.CodeBlock("val x = 1 > 0", "kotlin")))).toMatrix()

        assertEquals("<pre><code class=\"language-kotlin\">val x = 1 &gt; 0</code></pre>", rendered.formattedBody)
    }

    @Test
    fun `links render as anchors with an escaped href`() {
        val rendered = Content(RichText(listOf(RichNode.Link("https://x.com?a=1&b=2", "the site")))).toMatrix()

        assertEquals("<a href=\"https://x.com?a=1&amp;b=2\">the site</a>", rendered.formattedBody)
    }
}
