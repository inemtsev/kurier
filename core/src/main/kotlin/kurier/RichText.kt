package kurier

/**
 * Platform-agnostic rich text AST. Adapters render it to the native dialect via each platform's
 * structured/entity API or markup: Telegram formatting entities, Discord markdown, Slack mrkdwn,
 * Matrix HTML.
 *
 * The node set grows in minor releases (mentions, quotes, spoilers are candidates). Renderers must
 * terminate their `when` with an `else` that degrades the unknown node via [toPlainText] — a new
 * node is source-breaking only for renderers that skipped that fallback. Adapter authors: the
 * cross-platform rendering matrix and its golden-test inputs live in kurier-testing-contract's
 * `RenderingSamples`.
 */
public sealed interface RichNode {
    public data class Text(public val value: String) : RichNode
    public data class Bold(public val children: List<RichNode>) : RichNode
    public data class Italic(public val children: List<RichNode>) : RichNode
    public data class Strikethrough(public val children: List<RichNode>) : RichNode
    public data class Code(public val value: String) : RichNode
    public data class CodeBlock(public val code: String, public val language: String? = null) : RichNode
    public data class Link(public val url: String, public val label: String? = null) : RichNode

    /**
     * The plain-text degradation every renderer routes unknown or unsupported nodes through.
     * Labeled links render as `label (url)` so the destination survives plain-text-only platforms.
     */
    public fun toPlainText(): String = when (this) {
        is Text -> value
        is Bold -> children.joinToString("") { it.toPlainText() }
        is Italic -> children.joinToString("") { it.toPlainText() }
        is Strikethrough -> children.joinToString("") { it.toPlainText() }
        is Code -> value
        is CodeBlock -> code
        is Link -> if (label == null || label == url) url else "$label ($url)"
    }
}

public data class RichText(public val nodes: List<RichNode>) {

    public fun toPlainText(): String = nodes.joinToString("") { it.toPlainText() }

    public companion object {
        public fun plain(text: String): RichText = RichText(listOf(RichNode.Text(text)))
    }
}
