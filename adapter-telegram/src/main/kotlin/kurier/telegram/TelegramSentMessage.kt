package kurier.telegram

import kurier.ChannelId
import kurier.Content
import kurier.MessageId
import kurier.SentMessage

/**
 * A message kurier sent to Telegram. [edit] re-renders [Content] via `editMessageText` —
 * the same call streaming-edit replies drive per token — and [delete] removes it.
 */
internal class TelegramSentMessage(
    private val api: TelegramApi,
    private val chatId: Long,
    private val messageId: Long,
    override val channelId: ChannelId,
) : SentMessage {
    override val id: MessageId = MessageId(messageId.toString())

    override suspend fun edit(content: Content) {
        val rendered = content.toTelegram()
        api.editMessageText(chatId, messageId, rendered.text, rendered.entities.ifEmpty { null })
    }

    override suspend fun delete() {
        api.deleteMessage(chatId, messageId)
    }
}
