package kurier.twitch

import kurier.testing.assertRenderingMatrix
import kotlin.test.Test

/** Twitch chat has no formatting, so every sample flattens to its plain-text projection. */
class TwitchRenderingMatrixTest {

    @Test
    fun `renders the matrix as plain text`() = assertRenderingMatrix(
        mapOf(
            "plain" to "just text",
            "bold" to "bold",
            "italic" to "italic",
            "boldInItalic" to "x",
            "inlineCode" to "code",
            "codeWithSpecials" to "a`b < c & d",
            "codeBacktick" to "a`",
            "codeBlock" to "line 1\nline 2",
            "codeBlockWithLang" to "val x = 1",
            "linkWithLabel" to "the site",
            "bareLink" to "https://x.com",
            "emptyBold" to "kept",
            "emoji" to "🎉 hi",
            "escaping" to "a * b _ c | d < e > f & g",
            "everything" to "see b i c l",
        ),
    ) { it.text }
}
