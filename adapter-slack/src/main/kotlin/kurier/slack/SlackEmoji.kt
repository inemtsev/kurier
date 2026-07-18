package kurier.slack

/*
 * Unicode ↔ shortcode translation for the common reaction set: Slack's reactions API speaks
 * shortcodes ("thumbsup"), kurier's contract is canonical unicode ("👍"). Deliberately small —
 * kurier stays thin; entries can grow in minor releases. Unmapped unicode is unsendable (no-op),
 * unmapped shortcodes pass through by name (custom workspace emoji have no unicode form).
 */
private val EMOJI_SHORTCODES: List<Pair<String, String>> = listOf(
    "👍" to "thumbsup",
    "👎" to "thumbsdown",
    "❤️" to "heart",
    "😂" to "joy",
    "🎉" to "tada",
    "👀" to "eyes",
    "🔥" to "fire",
    "✅" to "white_check_mark",
    "❌" to "x",
    "🙏" to "pray",
    "👋" to "wave",
    "🚀" to "rocket",
    "💯" to "100",
    "👏" to "clap",
    "🤔" to "thinking_face",
    "😄" to "smile",
    "😢" to "cry",
    "😮" to "open_mouth",
    "⭐" to "star",
    "➕" to "heavy_plus_sign",
)

// Outbound lookup ignores the emoji-presentation variation selector (U+FE0F) so "❤" and "❤️" both map.
private const val VARIATION_SELECTOR = "\uFE0F"

private val TO_SHORTCODE: Map<String, String> =
    EMOJI_SHORTCODES.associate { (emoji, name) -> emoji.replace(VARIATION_SELECTOR, "") to name }

private val TO_EMOJI: Map<String, String> = EMOJI_SHORTCODES.associate { (emoji, name) -> name to emoji }

private const val ASCII_LIMIT = 128

/**
 * The shortcode to send for [emoji]: mapped unicode translates, ASCII input is already a shortcode
 * and passes through, anything else is unsendable — null, degrading to a no-op.
 */
internal fun emojiToSlackName(emoji: String): String? =
    TO_SHORTCODE[emoji.replace(VARIATION_SELECTOR, "")]
        ?: emoji.takeIf { it.isNotEmpty() && it.all { char -> char.code < ASCII_LIMIT } }

/** The canonical unicode for an inbound shortcode; unmapped shortcodes pass through by name. */
internal fun slackNameToEmoji(name: String): String = TO_EMOJI[name] ?: name
