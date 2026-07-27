package kurier.slack

import com.slack.api.model.event.MessageEvent
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

    @Test
    fun `an own-channel reply target maps to thread_ts, a foreign one degrades to a plain send`() = runTest {
        val sender = RecordingSlackSender()
        val channel = SlackChannel(sender, ChannelId("slack:C42"), PlatformId("slack"), ChannelKind.GROUP, name = null)

        channel.send(Content(RichText.plain("in thread"), replyTo = MessageRef(ChannelId("slack:C42"), MessageId("1700.1"))))
        channel.send(Content(RichText.plain("foreign"), replyTo = MessageRef(ChannelId("discord:9"), MessageId("8"))))

        assertEquals(listOf("1700.1", null), sender.threads)
    }

    @Test
    fun `replying to a threaded message targets the thread root, not the message itself`() = runTest {
        val sender = RecordingSlackSender()
        val channel = SlackChannel(sender, ChannelId("slack:C42"), PlatformId("slack"), ChannelKind.GROUP, name = null)
        val threadedMessage = SlackIncomingMessage(
            event = MessageEvent().apply {
                this.channel = "C42"
                user = "U200"
                text = "inside a thread"
                ts = "1700.5"
                threadTs = "1700.1" // the thread's root
            },
            channel = channel,
            isDirectedAtBot = true,
        )

        threadedMessage.reply(Content.text("answer"))

        assertEquals(listOf<String?>("1700.1"), sender.threads, "the reply must land in the thread, not fork a new one")
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
        override suspend fun send(text: String, threadTs: String?): MessageId = fail()

        override suspend fun edit(messageId: MessageId, text: String): Unit = fail()

        override suspend fun delete(messageId: MessageId): Unit = fail()

        override suspend fun react(messageId: MessageId, name: String): Unit = fail()

        private fun fail(): Nothing = throw SlackApiCallException("chat.postMessage", "channel_not_found")
    }

    private class RecordingSlackSender : SlackSender {
        val texts: MutableList<String> = mutableListOf()
        val threads: MutableList<String?> = mutableListOf()
        private var counter = 0

        override suspend fun send(text: String, threadTs: String?): MessageId {
            texts += text
            threads += threadTs
            return MessageId("s-${counter++}")
        }

        override suspend fun edit(messageId: MessageId, text: String) {
            texts += text
        }

        override suspend fun delete(messageId: MessageId) = Unit

        override suspend fun react(messageId: MessageId, name: String) = Unit
    }
}
