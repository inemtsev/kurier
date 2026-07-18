package kurier.slack

import kurier.Channel
import kurier.ChannelId
import kurier.ChannelKind
import kurier.MessageId
import kurier.PlatformId
import kurier.testing.contract.ChannelContract

/** Conformance of [SlackChannel] (an editing channel) to the shared [ChannelContract], via a fake sender. */
class SlackChannelContractTest : ChannelContract() {

    override fun newSubject(): Subject {
        val sender = RecordingSlackSender()
        val channel = SlackChannel(
            sender = sender,
            id = ChannelId("slack:C42"),
            platform = PlatformId("slack"),
            kind = ChannelKind.GROUP,
            name = null,
        )
        return Subject(channel) { sender.texts }
    }

    override fun newFailingChannel(): Channel =
        SlackChannel(
            sender = FailingSlackSender(),
            id = ChannelId("slack:C42"),
            platform = PlatformId("slack"),
            kind = ChannelKind.GROUP,
            name = null,
        )

    /** Mirrors [MethodsSlackSender]'s failure mode: it raises the adapter's [kurier.KurierException] subtype. */
    private class FailingSlackSender : SlackSender {
        override suspend fun send(text: String): MessageId = fail()

        override suspend fun edit(messageId: MessageId, text: String): Unit = fail()

        override suspend fun delete(messageId: MessageId): Unit = fail()

        override suspend fun react(messageId: MessageId, name: String): Unit = fail()

        private fun fail(): Nothing = throw SlackApiCallException("chat.postMessage", "channel_not_found")
    }

    private class RecordingSlackSender : SlackSender {
        val texts: MutableList<String> = mutableListOf()
        private var counter = 0

        override suspend fun send(text: String): MessageId {
            texts += text
            return MessageId("s-${counter++}")
        }

        override suspend fun edit(messageId: MessageId, text: String) {
            texts += text
        }

        override suspend fun delete(messageId: MessageId) = Unit

        override suspend fun react(messageId: MessageId, name: String) = Unit
    }
}
