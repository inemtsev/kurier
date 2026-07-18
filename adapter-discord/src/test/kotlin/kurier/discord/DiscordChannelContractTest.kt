package kurier.discord

import kurier.Channel
import kurier.ChannelId
import kurier.ChannelKind
import kurier.MessageId
import kurier.PlatformId
import kurier.testing.contract.ChannelContract

/** Conformance of [DiscordChannel] (an editing channel) to the shared [ChannelContract], via a fake sender. */
class DiscordChannelContractTest : ChannelContract() {

    override fun newSubject(): Subject {
        val sender = RecordingDiscordSender()
        val channel = DiscordChannel(
            sender = sender,
            id = ChannelId("discord:42"),
            platform = PlatformId("discord"),
            kind = ChannelKind.GROUP,
            name = null,
        )
        return Subject(channel) { sender.texts }
    }

    override fun newFailingChannel(): Channel =
        DiscordChannel(
            sender = FailingDiscordSender(),
            id = ChannelId("discord:42"),
            platform = PlatformId("discord"),
            kind = ChannelKind.GROUP,
            name = null,
        )

    /** Mirrors [KordDiscordSender]'s failure mode: it raises the adapter's [kurier.KurierException] subtype. */
    private class FailingDiscordSender : DiscordSender {
        override suspend fun send(text: String): MessageId = fail()

        override suspend fun edit(messageId: MessageId, text: String): Unit = fail()

        override suspend fun delete(messageId: MessageId): Unit = fail()

        override suspend fun typing(): Unit = fail()

        private fun fail(): Nothing = throw DiscordApiException("createMessage", cause = null, retryable = false)
    }

    private class RecordingDiscordSender : DiscordSender {
        val texts: MutableList<String> = mutableListOf()
        private var counter = 0

        override suspend fun send(text: String): MessageId {
            texts += text
            return MessageId("d-${counter++}")
        }

        override suspend fun edit(messageId: MessageId, text: String) {
            texts += text
        }

        override suspend fun delete(messageId: MessageId) = Unit

        override suspend fun typing() = Unit
    }
}
