package kurier.discord

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.MessageBehavior
import dev.kord.core.behavior.channel.MessageChannelBehavior
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.edit
import dev.kord.rest.request.RestRequestException
import kurier.KurierException
import kurier.MessageId
import java.io.IOException

/**
 * The outbound operations a [DiscordChannel] needs, abstracted away from Kord. Kord can't be built
 * without a live gateway, so this seam lets the channel's send/edit/stream logic be unit-tested with a
 * fake — the real implementation ([KordDiscordSender]) is the only Kord-touching part.
 */
internal interface DiscordSender {
    suspend fun send(text: String): MessageId
    suspend fun edit(messageId: MessageId, text: String)
    suspend fun delete(messageId: MessageId)
    suspend fun typing()
}

/** The real [DiscordSender], backed by Kord behaviors against one channel. */
internal class KordDiscordSender(
    private val kord: Kord,
    private val channelId: Snowflake,
) : DiscordSender {

    override suspend fun send(text: String): MessageId =
        call("createMessage") { MessageId(MessageChannelBehavior(channelId, kord).createMessage { content = text }.id.toString()) }

    override suspend fun edit(messageId: MessageId, text: String) {
        call("editMessage") { MessageBehavior(channelId, Snowflake(messageId.value), kord).edit { content = text } }
    }

    override suspend fun delete(messageId: MessageId) {
        call("deleteMessage") { MessageBehavior(channelId, Snowflake(messageId.value), kord).delete() }
    }

    override suspend fun typing() {
        call("triggerTypingIndicator") { MessageChannelBehavior(channelId, kord).type() }
    }

    /** Maps Kord and transport failures onto the [KurierException] contract; `429`/5xx are retryable. */
    private suspend fun <T> call(method: String, block: suspend () -> T): T = try {
        block()
    } catch (failure: RestRequestException) {
        val code = failure.status.code
        throw DiscordApiException(method, failure, retryable = code == TOO_MANY_REQUESTS || code >= SERVER_ERROR)
    } catch (failure: IOException) {
        throw DiscordApiException(method, failure, retryable = true)
    }

    private companion object {
        const val TOO_MANY_REQUESTS = 429
        const val SERVER_ERROR = 500
    }
}

/** Raised when Discord rejects a REST call — wraps Kord's [RestRequestException] per the [KurierException] contract. */
internal class DiscordApiException(
    method: String,
    cause: Throwable?,
    retryable: Boolean,
) : KurierException("Discord API $method failed: ${cause?.message ?: "unknown error"}", cause, retryable)
