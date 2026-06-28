package kurier.twitch

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * The meaning of one EventSub WebSocket text frame, decoded from the generic envelope. Keeping the
 * decode pure (no side effects) lets [TwitchConnection] stay a thin interpreter and makes the frame
 * taxonomy unit-testable without a live socket.
 */
internal sealed interface FrameAction {
    /** `session_welcome`: the session is ready — create the subscription and adopt its keepalive window. */
    data class Welcome(val sessionId: String, val keepaliveSeconds: Int?) : FrameAction

    /** `notification` carrying a chat message for our subscription. */
    data class Notification(val event: ChatMessageEvent) : FrameAction

    /** `session_reconnect`: Twitch is rotating the connection — drop this socket and reconnect. */
    data object Reconnect : FrameAction

    /** `revocation`: the subscription is gone for good (e.g. authorization revoked). */
    data class Revoked(val status: String) : FrameAction

    /** `session_keepalive` (a healthy heartbeat) and any frame type we don't act on. */
    data object Ignore : FrameAction
}

/** Classifies a raw EventSub text frame. Unknown types and keepalives map to [FrameAction.Ignore]. */
internal fun parseFrame(json: Json, text: String): FrameAction {
    val message = json.decodeFromString<EventSubMessage>(text)
    return when (message.metadata.messageType) {
        "session_welcome" -> {
            val session = json.decodeFromJsonElement<WelcomePayload>(message.payload).session
            FrameAction.Welcome(session.id, session.keepaliveTimeoutSeconds)
        }

        "session_reconnect" -> FrameAction.Reconnect

        "notification" ->
            if (message.metadata.subscriptionType == TwitchApi.CHAT_MESSAGE_TYPE) {
                FrameAction.Notification(json.decodeFromJsonElement<NotificationPayload>(message.payload).event)
            } else {
                FrameAction.Ignore
            }

        "revocation" ->
            FrameAction.Revoked(json.decodeFromJsonElement<RevocationPayload>(message.payload).subscription.status)

        else -> FrameAction.Ignore
    }
}
