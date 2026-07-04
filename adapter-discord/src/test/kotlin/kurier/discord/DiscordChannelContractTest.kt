package kurier.discord

import kurier.ChannelId
import kurier.ChannelKind
import kurier.MessageId
import kurier.PlatformId
import kurier.testing.ChannelContract

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
