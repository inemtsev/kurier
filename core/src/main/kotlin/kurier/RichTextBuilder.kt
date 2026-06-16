package kurier

/** Scopes the [RichTextBuilder] receiver so a nested block can't accidentally call the outer builder. */
@DslMarker
public annotation class RichTextDsl

/**
 * Builds [RichText] with a small typed DSL: `richText { text("hi "); bold("there") }`.
 * Formatting is added by explicitly named methods (one per node) rather than an operator —
 * a deliberate choice for callers that generate this code (agents/LLMs), where a uniform
 * "one call per node" shape is easier to emit correctly than an overloaded `+`.
 */
public fun richText(block: RichTextBuilder.() -> Unit): RichText = RichText(RichTextBuilder().apply(block).build())

@RichTextDsl
public class RichTextBuilder {
    private val nodes = mutableListOf<RichNode>()

    public fun text(value: String) {
        nodes += RichNode.Text(value)
    }

    public fun bold(value: String) {
        nodes += RichNode.Bold(listOf(RichNode.Text(value)))
    }

    /** Nested form, e.g. `bold { italic("x") }`, since bold/italic spans can contain other nodes. */
    public fun bold(block: RichTextBuilder.() -> Unit) {
        nodes += RichNode.Bold(RichTextBuilder().apply(block).build())
    }

    public fun italic(value: String) {
        nodes += RichNode.Italic(listOf(RichNode.Text(value)))
    }

    public fun italic(block: RichTextBuilder.() -> Unit) {
        nodes += RichNode.Italic(RichTextBuilder().apply(block).build())
    }

    public fun code(value: String) {
        nodes += RichNode.Code(value)
    }

    public fun codeBlock(code: String, language: String? = null) {
        nodes += RichNode.CodeBlock(code, language)
    }

    public fun link(url: String, label: String? = null) {
        nodes += RichNode.Link(url, label)
    }

    internal fun build(): List<RichNode> = nodes.toList()
}
