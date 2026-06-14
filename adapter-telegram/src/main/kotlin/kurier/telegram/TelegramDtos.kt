package kurier.telegram

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Bot API DTOs — the minimal getUpdates subset. Internal on purpose: these never
 * leak into kurier's core model (SPI rule #5). Unknown fields are ignored by the
 * deserializer, so the Bot API can grow without breaking us.
 */

/** Generic envelope wrapping every Bot API response: `{ ok, result, error_code?, description? }`. */
@Serializable
internal data class Response<T>(
    val ok: Boolean,
    val result: T? = null,
    @SerialName("error_code") val errorCode: Int? = null,
    val description: String? = null,
)

@Serializable
internal data class Update(
    @SerialName("update_id") val updateId: Long,
    val message: Message? = null,
)

@Serializable
internal data class Message(
    @SerialName("message_id") val messageId: Long,
    val from: User? = null,
    val chat: Chat,
    // Unix seconds; currently only reachable via raw — reserved for a future IncomingMessage.timestamp.
    val date: Long,
    val text: String? = null,
    @SerialName("reply_to_message") val replyToMessage: Message? = null,
)

@Serializable
internal data class Chat(
    val id: Long,
    val type: String,
    val title: String? = null,
    val username: String? = null,
)

@Serializable
internal data class User(
    val id: Long,
    @SerialName("is_bot") val isBot: Boolean = false,
    @SerialName("first_name") val firstName: String? = null,
    val username: String? = null,
)
