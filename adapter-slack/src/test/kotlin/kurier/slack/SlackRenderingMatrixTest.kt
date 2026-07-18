package kurier.slack

import kurier.Content
import kurier.testing.contract.assertRenderingMatrix
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Slack renders mrkdwn; the golden documents the escaping (only `&` `<` `>` — there is no escape for
 * `*`/`_`, which stay literal) and the degradations: backtick-bearing inline code falls back to plain
 * text, and code-block languages are dropped.
 */
class SlackRenderingMatrixTest {

    @Test
    fun `renders the matrix as mrkdwn`() = assertRenderingMatrix(
        mapOf(
            "plain" to "just text",
            "bold" to "*bold*",
            "italic" to "_italic_",
            "boldInItalic" to "_*x*_",
            "inlineCode" to "`code`",
            "codeWithSpecials" to "a`b &lt; c &amp; d",
            "codeBacktick" to "a`",
            "codeBlock" to "```\nline 1\nline 2\n```",
            "codeBlockWithLang" to "```\nval x = 1\n```",
            "linkWithLabel" to "<https://x.com|the site>",
            "bareLink" to "<https://x.com>",
            "emptyBold" to "kept",
            "emoji" to "🎉 *hi*",
            "escaping" to "a * b _ c | d &lt; e &gt; f &amp; g",
            "everything" to "see *b* _i_ `c` <https://x.com|l>",
        ),
    ) { it.toSlack() }

    @Test
    fun `escapes the ampersand family inside code too`() {
        // Both backtick matrix samples degrade to plain text, so they don't exercise in-code escaping.
        assertEquals("`x &lt; y`", Content.rich { code("x < y") }.toSlack())
        assertEquals("```\na &amp; b\n```", Content.rich { codeBlock("a & b") }.toSlack())
    }
}
