package kurier.discord

import kurier.Content
import kurier.RichNode
import kurier.RichText

/**
 * Renders a [RichText] to a Discord Markdown string. Unlike Telegram's entities, Discord formatting
 * is inline characters, so plain text must be escaped or it injects formatting. Three contexts have
 * different rules: regular text backslash-escapes specials; inline code can't be escaped (backticks
 * are handled by widening the fence); code blocks can't escape a literal ``` (deferred to M3, with
 * masked links — Discord doesn't render `[label](url)` in normal messages, so links degrade).
 */
internal fun Content.toDiscord(): String = rich.toDiscord()

internal fun RichText.toDiscord(): String = buildString { nodes.forEach { it.render(this) } }

private fun RichNode.render(out: StringBuilder) {
    when (this) {
        is RichNode.Text -> out.append(escapeMarkdown(value))
        is RichNode.Bold -> {
            out.append("**")
            children.forEach { it.render(out) }
            out.append("**")
        }

        is RichNode.Italic -> {
            out.append("*")
            children.forEach { it.render(out) }
            out.append("*")
        }

        is RichNode.Code -> out.append(renderInlineCode(value))
        is RichNode.CodeBlock -> out.append("```").append(language.orEmpty()).append('\n').append(code).append("\n```")
        is RichNode.Link -> out.append(renderLink(url, label))
    }
}

// The always-inline formatting delimiters; backslash first so we don't double-escape our own escapes.
private val MARKDOWN_SPECIALS = listOf('\\', '`', '*', '_', '~', '|')

private fun escapeMarkdown(text: String): String = buildString(text.length) {
    for (char in text) {
        if (char in MARKDOWN_SPECIALS) append('\\')
        append(char)
    }
}

// Inline code is literal — backslashes don't escape — so a backtick run is fenced by a longer one,
// padding with a space when the content starts or ends with a backtick (CommonMark code-span rule).
private fun renderInlineCode(value: String): String {
    val longestRun = Regex("`+").findAll(value).maxOfOrNull { it.value.length } ?: 0
    val fence = "`".repeat(longestRun + 1)
    val body = if (value.startsWith('`') || value.endsWith('`')) " $value " else value
    return "$fence$body$fence"
}

// Discord renders masked links only in embeds, not normal messages, so a labeled link degrades to
// "label (url)"; a bare or self-labeled url is emitted as-is (Discord auto-links it).
private fun renderLink(url: String, label: String?): String =
    if (label == null || label == url) url else "${escapeMarkdown(label)} ($url)"
