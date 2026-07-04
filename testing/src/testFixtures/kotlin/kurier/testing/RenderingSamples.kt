package kurier.testing

import kurier.Content
import kurier.RichNode
import kurier.RichText
import kotlin.test.assertEquals

/**
 * The canonical `RichText` inputs for the rendering matrix — one shared set that every adapter renders
 * and golden-checks, so each node type and edge case (nesting, escaping, empty spans, surrogate pairs)
 * is covered uniformly across platforms. Drive them with [assertRenderingMatrix].
 */
public object RenderingSamples {

    public class Sample(public val name: String, public val content: Content)

    public val all: List<Sample> = listOf(
        Sample("plain", Content.text("just text")),
        Sample("bold", Content.rich { bold("bold") }),
        Sample("italic", Content.rich { italic("italic") }),
        Sample("boldInItalic", Content.rich { italic { bold("x") } }),
        Sample("inlineCode", Content.rich { code("code") }),
        Sample("codeWithSpecials", Content.rich { code("a`b < c & d") }),
        Sample("codeBacktick", Content.rich { code("a`") }),
        Sample("codeBlock", Content.rich { codeBlock("line 1\nline 2") }),
        Sample("codeBlockWithLang", Content.rich { codeBlock("val x = 1", "kotlin") }),
        Sample("linkWithLabel", Content.rich { link("https://x.com", "the site") }),
        Sample("bareLink", Content.rich { link("https://x.com") }),
        Sample("emptyBold", Content(RichText(listOf(RichNode.Bold(emptyList()), RichNode.Text("kept"))))),
        Sample(
            "emoji",
            Content.rich {
                text("🎉 ")
                bold("hi")
            },
        ),
        Sample("escaping", Content.text("a * b _ c | d < e > f & g")),
        Sample(
            "everything",
            Content.rich {
                text("see ")
                bold("b")
                text(" ")
                italic("i")
                text(" ")
                code("c")
                text(" ")
                link("https://x.com", "l")
            },
        ),
    )
}

/**
 * Asserts that [render] maps every [RenderingSamples] input to its [expected] output. Also checks
 * [expected] covers exactly the sample set — so adding a sample forces every platform's golden to
 * account for it, keeping the matrix exhaustive.
 */
public fun <T> assertRenderingMatrix(expected: Map<String, T>, render: (Content) -> T) {
    assertEquals(
        RenderingSamples.all.map { it.name }.toSet(),
        expected.keys,
        "the golden must cover exactly the shared sample set",
    )
    RenderingSamples.all.forEach { sample ->
        assertEquals(expected.getValue(sample.name), render(sample.content), "rendering of '${sample.name}'")
    }
}
