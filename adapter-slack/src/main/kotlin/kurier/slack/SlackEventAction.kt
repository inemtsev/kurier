package kurier.slack

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.slack.api.model.event.MessageDeletedEvent
import com.slack.api.model.event.MessageEvent
import com.slack.api.model.event.MessageFileShareEvent
import com.slack.api.model.event.MessageThreadBroadcastEvent
import com.slack.api.model.event.ReactionAddedEvent
import com.slack.api.model.event.ReactionRemovedEvent

/**
 * The meaning of one Events API envelope payload, decoded from its generic JSON. Keeping the decode
 * pure (no side effects) lets [SlackConnection] stay a thin interpreter and makes the event taxonomy
 * unit-testable without a live socket. `slack-api-client` ships no payload dispatcher (that's Bolt's),
 * so the `event.type`/`event.subtype` dispatch lives here, deserializing into the SDK's model classes.
 */
internal sealed interface SlackEventAction {
    /** A user-visible `message` event — plain, a file share, or a reply broadcast to the channel. */
    data class Message(val event: MessageEvent) : SlackEventAction

    /** `message` with subtype `message_deleted`. */
    data class Deleted(val event: MessageDeletedEvent) : SlackEventAction

    data class ReactionAdded(val event: ReactionAddedEvent) : SlackEventAction

    data class ReactionRemoved(val event: ReactionRemovedEvent) : SlackEventAction

    /**
     * Everything we don't act on. Notably `app_mention` — the recommended app config subscribes to it
     * alongside `message.channels`, so acting on both would deliver every channel mention twice — and
     * `message_changed`, which would otherwise echo each of the bot's own streaming edits back at it.
     */
    data object Ignore : SlackEventAction
}

/** Classifies an Events API envelope payload. Unknown or malformed shapes map to [SlackEventAction.Ignore]. */
internal fun parseEnvelope(gson: Gson, payload: JsonElement?): SlackEventAction {
    val event = (payload as? JsonObject)?.get("event") as? JsonObject ?: return SlackEventAction.Ignore
    return when (event.stringOrNull("type")) {
        MessageEvent.TYPE_NAME -> when (event.stringOrNull("subtype")) {
            // file_share and thread_broadcast are genuine user messages (carrying files / a reply also
            // shown in the channel); MessageEvent has all their shared fields, so they normalize alike.
            null,
            MessageFileShareEvent.SUBTYPE_NAME,
            MessageThreadBroadcastEvent.SUBTYPE_NAME,
            -> SlackEventAction.Message(gson.fromJson(event, MessageEvent::class.java))

            MessageDeletedEvent.SUBTYPE_NAME -> SlackEventAction.Deleted(gson.fromJson(event, MessageDeletedEvent::class.java))
            else -> SlackEventAction.Ignore
        }

        ReactionAddedEvent.TYPE_NAME -> SlackEventAction.ReactionAdded(gson.fromJson(event, ReactionAddedEvent::class.java))

        ReactionRemovedEvent.TYPE_NAME -> SlackEventAction.ReactionRemoved(gson.fromJson(event, ReactionRemovedEvent::class.java))

        else -> SlackEventAction.Ignore
    }
}

/** True for the `{"type":"hello"}` frame Slack sends when a Socket Mode session opens. */
internal fun isHelloFrame(gson: Gson, text: String): Boolean =
    runCatching { gson.fromJson(text, JsonObject::class.java).stringOrNull("type") == "hello" }.getOrDefault(false)

private fun JsonObject.stringOrNull(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString
