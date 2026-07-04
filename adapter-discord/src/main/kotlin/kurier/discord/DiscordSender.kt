package kurier.discord

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.MessageBehavior
import dev.kord.core.behavior.channel.MessageChannelBehavior
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.edit
import kurier.MessageId

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
        MessageId(MessageChannelBehavior(channelId, kord).createMessage { content = text }.id.toString())

    override suspend fun edit(messageId: MessageId, text: String) {
        MessageBehavior(channelId, Snowflake(messageId.value), kord).edit { content = text }
    }

    override suspend fun delete(messageId: MessageId) {
        MessageBehavior(channelId, Snowflake(messageId.value), kord).delete()
    }

    override suspend fun typing() {
        MessageChannelBehavior(channelId, kord).type()
    }
}
