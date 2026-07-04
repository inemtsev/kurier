package kurier.matrix

import kurier.testing.assertRenderingMatrix
import kotlin.test.Test

/** Matrix renders a plain `body` plus an optional HTML `formatted_body` (null for pure-plain text). */
class MatrixRenderingMatrixTest {

    @Test
    fun `renders the matrix as body plus optional html`() = assertRenderingMatrix(
        mapOf(
            "plain" to RenderedMatrix("just text", null),
            "bold" to RenderedMatrix("bold", "<strong>bold</strong>"),
            "italic" to RenderedMatrix("italic", "<em>italic</em>"),
            "boldInItalic" to RenderedMatrix("x", "<em><strong>x</strong></em>"),
            "inlineCode" to RenderedMatrix("code", "<code>code</code>"),
            "codeWithSpecials" to RenderedMatrix("a`b < c & d", "<code>a`b &lt; c &amp; d</code>"),
            "codeBacktick" to RenderedMatrix("a`", "<code>a`</code>"),
            "codeBlock" to RenderedMatrix("line 1\nline 2", "<pre><code>line 1\nline 2</code></pre>"),
            "codeBlockWithLang" to RenderedMatrix(
                "val x = 1",
                "<pre><code class=\"language-kotlin\">val x = 1</code></pre>",
            ),
            "linkWithLabel" to RenderedMatrix("the site", "<a href=\"https://x.com\">the site</a>"),
            "bareLink" to RenderedMatrix("https://x.com", "<a href=\"https://x.com\">https://x.com</a>"),
            "emptyBold" to RenderedMatrix("kept", null),
            "emoji" to RenderedMatrix("🎉 hi", "🎉 <strong>hi</strong>"),
            "escaping" to RenderedMatrix("a * b _ c | d < e > f & g", null),
            "everything" to RenderedMatrix(
                "see b i c l",
                "see <strong>b</strong> <em>i</em> <code>c</code> <a href=\"https://x.com\">l</a>",
            ),
        ),
    ) { it.toMatrix() }
}
