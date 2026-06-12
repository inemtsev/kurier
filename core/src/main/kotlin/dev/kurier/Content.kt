package dev.kurier

public data class Attachment(
    public val fileName: String? = null,
    public val contentType: String? = null,
    public val url: String? = null,
)

/** Outgoing message content. */
public data class Content(
    public val rich: RichText,
    public val attachments: List<Attachment> = emptyList(),
) {
    public val text: String get() = rich.toPlainText()

    public companion object {
        public fun text(value: String): Content = Content(RichText.plain(value))
    }
}
