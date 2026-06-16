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
    @SerialName("sender_chat") val senderChat: Chat? = null,
    val chat: Chat,
    // Unix seconds; currently only reachable via raw — reserved for a future IncomingMessage.timestamp.
    val date: Long,
    val text: String? = null,
    val entities: List<MessageEntity>? = null,
    val photo: List<PhotoSize>? = null,
    val document: Document? = null,
    @SerialName("reply_to_message") val replyToMessage: Message? = null,
)

/** A formatted span within a message's text (bold, link, mention, …); offsets are UTF-16 units. */
@Serializable
internal data class MessageEntity(
    val type: String,
    val offset: Int,
    val length: Int,
    val url: String? = null,
    val language: String? = null,
    val user: User? = null,
)

/** One size of a photo; the array is ordered smallest→largest, so the last is the original. */
@Serializable
internal data class PhotoSize(
    @SerialName("file_id") val fileId: String,
)

@Serializable
internal data class Document(
    @SerialName("file_id") val fileId: String,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
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
    @SerialName("last_name") val lastName: String? = null,
    val username: String? = null,
)

/**
 * Outbound: `sendMessage` request body. [entities] carries kurier's rendered RichText spans —
 * Telegram's structured formatting API, so no MarkdownV2 escaping and no injection surface.
 */
@Serializable
internal data class SendMessageRequest(
    @SerialName("chat_id") val chatId: Long,
    val text: String,
    val entities: List<MessageEntity>? = null,
)

/** Outbound: `editMessageText` request body — the basis of streaming-edit replies. */
@Serializable
internal data class EditMessageTextRequest(
    @SerialName("chat_id") val chatId: Long,
    @SerialName("message_id") val messageId: Long,
    val text: String,
    val entities: List<MessageEntity>? = null,
)

/** Outbound: `deleteMessage` request body. */
@Serializable
internal data class DeleteMessageRequest(
    @SerialName("chat_id") val chatId: Long,
    @SerialName("message_id") val messageId: Long,
)

/** Outbound: `sendChatAction` request body (e.g. `action = "typing"`). */
@Serializable
internal data class SendChatActionRequest(
    @SerialName("chat_id") val chatId: Long,
    val action: String,
)

/** Outbound: `setMessageReaction` request body. */
@Serializable
internal data class SetMessageReactionRequest(
    @SerialName("chat_id") val chatId: Long,
    @SerialName("message_id") val messageId: Long,
    val reaction: List<ReactionTypeEmoji>,
)

/** A single emoji reaction. [type] has no default so it is always serialized (the Bot API requires it). */
@Serializable
internal data class ReactionTypeEmoji(
    val type: String,
    val emoji: String,
)
