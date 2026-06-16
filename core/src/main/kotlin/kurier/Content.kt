package kurier

public data class Attachment(
    public val fileName: String? = null,
    public val contentType: String? = null,
    public val url: String? = null,
    /** Platform's opaque file handle (e.g. Telegram `file_id`); resolve to [url] via the adapter when needed. */
    public val id: String? = null,
)

/** Outgoing message content. */
public data class Content(
    public val rich: RichText,
    public val attachments: List<Attachment> = emptyList(),
) {
    public val text: String get() = rich.toPlainText()

    public companion object {
        public fun text(value: String): Content = Content(RichText.plain(value))

        /** Builds [Content] from the [richText] DSL, e.g. `Content.rich { bold("done") }`. */
        public fun rich(block: RichTextBuilder.() -> Unit): Content = Content(richText(block))
    }
}
