package kurier

/**
 * A file attached to an **inbound** message — the received-file record on [IncomingMessage.attachments].
 * Outbound file upload ships post-0.1.0 as a distinct type (with a byte/stream source), not by reusing
 * this shape.
 *
 * Deliberately not a data class: its fields will grow (sizes, captions, spoiler flags), and a data
 * class's `copy`/`componentN` would turn every added field into a binary break. Follows [Content]'s
 * evolution rule.
 */
public class Attachment(
    public val fileName: String? = null,
    public val contentType: String? = null,
    public val url: String? = null,
    /** Platform's opaque file handle (e.g. Telegram `file_id`); resolve to [url] via the adapter when needed. */
    public val id: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is Attachment &&
            fileName == other.fileName &&
            contentType == other.contentType &&
            url == other.url &&
            id == other.id

    override fun hashCode(): Int = listOf(fileName, contentType, url, id).hashCode()

    override fun toString(): String = "Attachment(fileName=$fileName, contentType=$contentType, url=$url, id=$id)"
}

/**
 * Outgoing message content.
 *
 * Deliberately not a data class: this is the API's designated growth point (buttons, thread
 * targets, …), and a data class's `copy`/`componentN` would turn every added field into a binary
 * break. Evolution rule: new fields are appended as trailing parameters with defaults, plus a
 * `@Deprecated(level = HIDDEN)` secondary constructor preserving each previously published
 * signature for already-compiled callers.
 */
public class Content(
    public val rich: RichText,
    /**
     * The message this content replies to, where the platform can express it (Telegram
     * `reply_to_message_id`, Slack `thread_ts`, Discord message references, Matrix
     * `m.in_reply_to`). Adapters that haven't wired reply-linking yet ignore it — the
     * message still sends, unlinked (capability rule: degrade, never throw).
     */
    public val replyTo: MessageRef? = null,
) {
    public val text: String get() = rich.toPlainText()

    override fun equals(other: Any?): Boolean =
        other is Content && rich == other.rich && replyTo == other.replyTo

    override fun hashCode(): Int = listOf(rich, replyTo).hashCode()

    override fun toString(): String = "Content(rich=$rich, replyTo=$replyTo)"

    public companion object {
        public fun text(value: String): Content = Content(RichText.plain(value))

        /** Builds [Content] from the [richText] DSL, e.g. `Content.rich { bold("done") }`. */
        public fun rich(block: RichTextBuilder.() -> Unit): Content = Content(richText(block))
    }
}

/** This content with [replyTo] set — reconstruction, since [Content] deliberately has no `copy`. */
public fun Content.withReplyTo(replyTo: MessageRef?): Content = Content(rich, replyTo)
