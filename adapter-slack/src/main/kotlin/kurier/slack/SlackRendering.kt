package kurier.slack

import kurier.Content
import kurier.RichNode
import kurier.RichText

/**
 * Renders a [RichText] to a Slack mrkdwn string. mrkdwn is not Markdown: bold is a *single* asterisk,
 * italic an underscore, links are `<url|label>`, and the only escapable characters are `&` `<` `>`
 * (there is no escape for `*` `_` `~`, so text shaped like formatting renders as formatting — the
 * golden documents that platform limitation). Inline code that itself contains a backtick cannot be
 * represented (no fence widening, no in-code escapes), so it degrades to escaped plain text.
 */
internal fun Content.toSlack(): String = rich.toSlack()

internal fun RichText.toSlack(): String = buildString { nodes.forEach { it.render(this) } }

private fun RichNode.render(out: StringBuilder) {
    when (this) {
        is RichNode.Text -> out.append(escapeMrkdwn(value))
        is RichNode.Bold -> out.wrapNonEmpty("*", children)
        is RichNode.Italic -> out.wrapNonEmpty("_", children)
        is RichNode.Code -> if (value.isNotEmpty()) out.append(renderInlineCode(value))
        is RichNode.CodeBlock -> if (code.isNotEmpty()) {
            // mrkdwn fences carry no language tag; the language is dropped.
            out.append("```\n").append(escapeMrkdwn(code)).append("\n```")
        }

        is RichNode.Link -> out.append(renderLink(url, label))
    }
}

// Wrap [children] in [delimiter] on both sides, but skip it entirely when they render to nothing —
// so an empty span (e.g. bold of blank generated text) leaves no stray "*" in the output.
private fun StringBuilder.wrapNonEmpty(delimiter: String, children: List<RichNode>) {
    val inner = buildString { children.forEach { it.render(this) } }
    if (inner.isNotEmpty()) append(delimiter).append(inner).append(delimiter)
}

// Slack requires escaping exactly these three, in every context (text, code, links); a single pass,
// so the '&' of an emitted entity is never escaped again.
private fun escapeMrkdwn(text: String): String = buildString(text.length) {
    for (char in text) {
        when (char) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            else -> append(char)
        }
    }
}

// Inline code is single-backtick only, so content containing a backtick degrades to escaped plain
// text: characters preserved exactly, only the decoration is lost. The literal backtick could pair
// with a later code span in the same message; accepted, like Discord's masked-link degradation.
private fun renderInlineCode(value: String): String =
    if ('`' in value) escapeMrkdwn(value) else "`${escapeMrkdwn(value)}`"

// <url|label> or <url>; a self-labeled link collapses to the bare form.
private fun renderLink(url: String, label: String?): String =
    if (label == null || label == url) "<${escapeMrkdwn(url)}>" else "<${escapeMrkdwn(url)}|${escapeMrkdwn(label)}>"
