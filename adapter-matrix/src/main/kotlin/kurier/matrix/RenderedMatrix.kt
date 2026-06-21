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
    val formatted = if (rich.nodes.all { it is RichNode.Text }) null else rich.toHtml()
    return RenderedMatrix(plain, formatted)
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
        is RichNode.Bold -> out.wrap("strong") { children.forEach { it.renderHtml(out) } }
        is RichNode.Italic -> out.wrap("em") { children.forEach { it.renderHtml(out) } }
        is RichNode.Code -> out.wrap("code") { out.append(escapeHtml(value)) }
        is RichNode.CodeBlock -> {
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
