package kurier.twitch

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * EventSub + Helix DTOs — the minimal subset kurier reads. Internal on purpose (SPI rule #5): they
 * never leak into core. Unknown fields are ignored by the deserializer, so Twitch can grow without
 * breaking us. The WS envelope is generic — [payload] is decoded per [EventSubMetadata.messageType].
 */
@Serializable
internal data class EventSubMessage(
    val metadata: EventSubMetadata,
    val payload: JsonObject,
)

@Serializable
internal data class EventSubMetadata(
    @SerialName("message_type") val messageType: String,
    @SerialName("subscription_type") val subscriptionType: String? = null,
)

/** `session_welcome` / `session_reconnect` payload. */
@Serializable
internal data class WelcomePayload(val session: Session)

@Serializable
internal data class Session(
    val id: String,
    @SerialName("keepalive_timeout_seconds") val keepaliveTimeoutSeconds: Int? = null,
    @SerialName("reconnect_url") val reconnectUrl: String? = null,
)

/** `notification` payload (the `subscription` object is ignored). */
@Serializable
internal data class NotificationPayload(val event: ChatMessageEvent)

/** `revocation` payload — Twitch dropped our subscription (authorization revoked, user removed, …). */
@Serializable
internal data class RevocationPayload(val subscription: RevokedSubscription)

@Serializable
internal data class RevokedSubscription(val status: String)

@Serializable
internal data class ChatMessageEvent(
    @SerialName("broadcaster_user_id") val broadcasterUserId: String,
    @SerialName("chatter_user_id") val chatterUserId: String,
    @SerialName("chatter_user_name") val chatterUserName: String,
    @SerialName("message_id") val messageId: String,
    val message: ChatMessage,
)

@Serializable
internal data class ChatMessage(
    val text: String,
    val fragments: List<Fragment> = emptyList(),
)

/** A message fragment: `text`, `emote`, `cheermote`, or `mention` (only `type` + `mention` are read). */
@Serializable
internal data class Fragment(
    val type: String,
    val mention: Mention? = null,
)

@Serializable
internal data class Mention(
    @SerialName("user_id") val userId: String,
)

/** `GET https://id.twitch.tv/oauth2/validate` response — the bot's own account. */
@Serializable
internal data class ValidateResponse(
    @SerialName("user_id") val userId: String,
    val login: String,
)

/** `POST https://id.twitch.tv/oauth2/token` (refresh grant) response; Twitch may rotate the refresh token. */
@Serializable
internal data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
)

/** `GET /helix/users` response. */
@Serializable
internal data class UsersResponse(val data: List<TwitchUser>)

@Serializable
internal data class TwitchUser(val id: String, val login: String)

/** `POST /helix/eventsub/subscriptions` request body. */
@Serializable
internal data class SubscriptionRequest(
    val type: String,
    val version: String,
    val condition: ChatCondition,
    val transport: Transport,
)

@Serializable
internal data class ChatCondition(
    @SerialName("broadcaster_user_id") val broadcasterUserId: String,
    @SerialName("user_id") val userId: String,
)

@Serializable
internal data class Transport(
    val method: String,
    @SerialName("session_id") val sessionId: String,
)

/** `POST /helix/chat/messages` request body. */
@Serializable
internal data class SendMessageRequest(
    @SerialName("broadcaster_id") val broadcasterId: String,
    @SerialName("sender_id") val senderId: String,
    val message: String,
)

/** `POST /helix/chat/messages` response — Twitch acks even a rejected message with `is_sent=false`. */
@Serializable
internal data class SendMessageResponse(val data: List<SendMessageResult>)

@Serializable
internal data class SendMessageResult(
    @SerialName("message_id") val messageId: String,
    @SerialName("is_sent") val isSent: Boolean,
    @SerialName("drop_reason") val dropReason: DropReason? = null,
)

@Serializable
internal data class DropReason(val code: String, val message: String)
