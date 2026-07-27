package kurier.discord

import kotlinx.coroutines.test.runTest
import kurier.Channel
import kurier.ChannelId
import kurier.ChannelKind
import kurier.Content
import kurier.MessageId
import kurier.MessageRef
import kurier.PlatformId
import kurier.RichText
import kurier.testing.contract.ChannelContract
import kotlin.test.Test
import kotlin.test.assertEquals

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

    @Test
    fun `an own-channel reply target becomes a message reference, a foreign one degrades to a plain send`() = runTest {
        val sender = RecordingDiscordSender()
        val channel = DiscordChannel(sender, ChannelId("discord:42"), PlatformId("discord"), ChannelKind.GROUP, name = null)

        channel.send(Content(RichText.plain("reply"), replyTo = MessageRef(ChannelId("discord:42"), MessageId("8"))))
        channel.send(Content(RichText.plain("foreign"), replyTo = MessageRef(ChannelId("slack:C1"), MessageId("1.2"))))

        assertEquals(listOf(MessageId("8"), null), sender.replies)
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
        override suspend fun send(text: String, replyToId: MessageId?): MessageId = fail()

        override suspend fun edit(messageId: MessageId, text: String): Unit = fail()

        override suspend fun delete(messageId: MessageId): Unit = fail()

        override suspend fun typing(): Unit = fail()

        private fun fail(): Nothing = throw DiscordApiException("createMessage", cause = null, retryable = false)
    }

    private class RecordingDiscordSender : DiscordSender {
        val texts: MutableList<String> = mutableListOf()
        val replies: MutableList<MessageId?> = mutableListOf()
        private var counter = 0

        override suspend fun send(text: String, replyToId: MessageId?): MessageId {
            texts += text
            replies += replyToId
            return MessageId("d-${counter++}")
        }

        override suspend fun edit(messageId: MessageId, text: String) {
            texts += text
        }

        override suspend fun delete(messageId: MessageId) = Unit

        override suspend fun typing() = Unit
    }
}
