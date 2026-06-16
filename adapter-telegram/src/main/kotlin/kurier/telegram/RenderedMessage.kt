package kurier.telegram

import kurier.Content
import kurier.RichNode
import kurier.RichText

/** Plain text plus the offset-based [MessageEntity] spans Telegram renders it with. */
internal data class RenderedMessage(
    val text: String,
    val entities: List<MessageEntity>,
)

internal fun Content.toTelegram(): RenderedMessage = rich.toTelegram()

/**
 * Renders a [RichText] to Telegram's structured `entities` API rather than MarkdownV2:
 * offsets are UTF-16 code units (which Kotlin strings already are), so no escaping and no
 * formatting-injection surface. Nested spans (e.g. bold-in-italic) emit overlapping entities,
 * which Telegram supports.
 */
internal fun RichText.toTelegram(): RenderedMessage {
    val text = StringBuilder()
    val entities = mutableListOf<MessageEntity>()
    nodes.forEach { it.render(text, entities) }
    return RenderedMessage(text.toString(), entities)
}

private fun RichNode.render(text: StringBuilder, entities: MutableList<MessageEntity>) {
    val start = text.length
    when (this) {
        is RichNode.Text -> text.append(value)
        is RichNode.Bold -> {
            children.forEach { it.render(text, entities) }
            entities.span("bold", start, text.length)
        }

        is RichNode.Italic -> {
            children.forEach { it.render(text, entities) }
            entities.span("italic", start, text.length)
        }

        is RichNode.Code -> {
            text.append(value)
            entities.span("code", start, text.length)
        }

        is RichNode.CodeBlock -> {
            text.append(code)
            entities.span("pre", start, text.length, language = language)
        }

        is RichNode.Link -> {
            text.append(label ?: url)
            entities.span("text_link", start, text.length, url = url)
        }
    }
}

// Zero-length entities are rejected by the Bot API, so skip empty spans (e.g. Bold with no text).
private fun MutableList<MessageEntity>.span(
    type: String,
    start: Int,
    end: Int,
    url: String? = null,
    language: String? = null,
) {
    if (end > start) add(MessageEntity(type = type, offset = start, length = end - start, url = url, language = language))
}
