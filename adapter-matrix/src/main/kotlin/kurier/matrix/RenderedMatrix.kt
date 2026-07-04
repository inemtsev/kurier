package kurier.matrix

import kurier.Content
import kurier.RichNode
import kurier.RichText
import net.folivo.trixnity.core.model.events.m.RelatesTo
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent

internal const val HTML_FORMAT: String = "org.matrix.custom.html"

/**
 * A Matrix message body in both representations: the plain-text [body] (the mandatory fallback every
 * Matrix message carries) and the optional HTML [formattedBody] for rich-capable clients.
 */
internal data class RenderedMatrix(
    val body: String,
    val formattedBody: String?,
)

/**
 * Renders [Content] to Matrix's body + `formatted_body`. Pure-plain text skips the HTML entirely
 * (so streamed tokens stay cheap); otherwise it renders to the HTML subset. Unlike Discord's markdown,
 * HTML escaping is uniform — `& < >` everywhere, plus `"` inside attributes — with no per-context rules.
 */
internal fun Content.toMatrix(): RenderedMatrix {
    val plain = rich.toPlainText()
    // Skip formatted_body when the HTML adds no markup — plain text, or formatting that guarded away
    // to nothing (e.g. an empty bold) — so we never send a redundant or stray-tag formatted body.
    val html = rich.toHtml()
    return RenderedMatrix(plain, formattedBody = html.takeIf { it != escapeHtml(plain) })
}

/** Bridges a [RenderedMatrix] to Trixnity's text content, setting `format` only when HTML is present. */
internal fun RenderedMatrix.toText(relatesTo: RelatesTo? = null): RoomMessageEventContent.TextBased.Text =
    RoomMessageEventContent.TextBased.Text(
        body = body,
        format = formattedBody?.let { HTML_FORMAT },
        formattedBody = formattedBody,
        relatesTo = relatesTo,
    )

private fun RichText.toHtml(): String = buildString { nodes.forEach { it.renderHtml(this) } }

private fun RichNode.renderHtml(out: StringBuilder) {
    when (this) {
        is RichNode.Text -> out.append(escapeHtml(value))
        is RichNode.Bold -> out.wrapNonEmpty("strong", children)
        is RichNode.Italic -> out.wrapNonEmpty("em", children)
        is RichNode.Code -> if (value.isNotEmpty()) out.wrap("code") { out.append(escapeHtml(value)) }
        is RichNode.CodeBlock -> if (code.isNotEmpty()) {
            out.append("<pre><code")
            language?.let { out.append(" class=\"language-").append(escapeAttribute(it)).append('"') }
            out.append('>').append(escapeHtml(code)).append("</code></pre>")
        }

        is RichNode.Link -> out.append("<a href=\"").append(escapeAttribute(url)).append("\">")
            .append(escapeHtml(label ?: url)).append("</a>")
    }
}

private inline fun StringBuilder.wrap(tag: String, body: () -> Unit) {
    append('<').append(tag).append('>')
    body()
    append("</").append(tag).append('>')
}

// Render [children] into a <tag>…</tag>, but skip the tag entirely when they render to nothing —
// so an empty span leaves no stray "<strong></strong>" in the formatted body.
private fun StringBuilder.wrapNonEmpty(tag: String, children: List<RichNode>) {
    val inner = buildString { children.forEach { it.renderHtml(this) } }
    if (inner.isNotEmpty()) wrap(tag) { append(inner) }
}

private fun escapeHtml(text: String): String = buildString(text.length) {
    for (char in text) {
        when (char) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            else -> append(char)
        }
    }
}

private fun escapeAttribute(text: String): String = buildString(text.length) {
    for (char in text) {
        when (char) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(char)
        }
    }
}
