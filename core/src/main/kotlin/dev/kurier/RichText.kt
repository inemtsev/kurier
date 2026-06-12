package dev.kurier

/**
 * Platform-agnostic rich text AST. Adapters render it to the native dialect:
 * MarkdownV2 (Telegram), markdown (Discord), mrkdwn (Slack).
 */
public sealed interface RichNode {
    public data class Text(public val value: String) : RichNode
    public data class Bold(public val children: List<RichNode>) : RichNode
    public data class Italic(public val children: List<RichNode>) : RichNode
    public data class Code(public val value: String) : RichNode
    public data class CodeBlock(public val code: String, public val language: String? = null) : RichNode
    public data class Link(public val url: String, public val label: String? = null) : RichNode
}

public data class RichText(public val nodes: List<RichNode>) {

    public fun toPlainText(): String = nodes.joinToString("") { it.toPlainText() }

    public companion object {
        public fun plain(text: String): RichText = RichText(listOf(RichNode.Text(text)))
    }
}

private fun RichNode.toPlainText(): String = when (this) {
    is RichNode.Text -> value
    is RichNode.Bold -> children.joinToString("") { it.toPlainText() }
    is RichNode.Italic -> children.joinToString("") { it.toPlainText() }
    is RichNode.Code -> value
    is RichNode.CodeBlock -> code
    is RichNode.Link -> label ?: url
}
