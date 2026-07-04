package kurier.discord

import kurier.testing.assertRenderingMatrix
import kotlin.test.Test

/** Discord renders escaped Markdown; the golden documents the escaping + link/empty-span degradations. */
class DiscordRenderingMatrixTest {

    @Test
    fun `renders the matrix as escaped Markdown`() = assertRenderingMatrix(
        mapOf(
            "plain" to "just text",
            "bold" to "**bold**",
            "italic" to "*italic*",
            "boldInItalic" to "***x***",
            "inlineCode" to "`code`",
            "codeWithSpecials" to "``a`b < c & d``",
            "codeBacktick" to "`` a` ``",
            "codeBlock" to "```\nline 1\nline 2\n```",
            "codeBlockWithLang" to "```kotlin\nval x = 1\n```",
            "linkWithLabel" to "the site (https://x.com)",
            "bareLink" to "https://x.com",
            "emptyBold" to "kept",
            "emoji" to "🎉 **hi**",
            "escaping" to "a \\* b \\_ c \\| d < e > f & g",
            "everything" to "see **b** *i* `c` l (https://x.com)",
        ),
    ) { it.toDiscord() }
}
