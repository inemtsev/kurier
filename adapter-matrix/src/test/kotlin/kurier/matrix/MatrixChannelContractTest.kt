package kurier.matrix

import kurier.ChannelId
import kurier.ChannelKind
import kurier.MessageId
import kurier.PlatformId
import kurier.testing.ChannelContract

/** Conformance of [MatrixChannel] (an editing channel) to the shared [ChannelContract], via a fake sender. */
class MatrixChannelContractTest : ChannelContract() {

    override fun newSubject(): Subject {
        val sender = RecordingMatrixSender()
        val channel = MatrixChannel(
            sender = sender,
            id = ChannelId("matrix:!room:hs"),
            platform = PlatformId("matrix"),
            kind = ChannelKind.GROUP,
            name = null,
        )
        // The plain-text body is what crosses platforms; the HTML body is rendering, asserted elsewhere.
        return Subject(channel) { sender.bodies }
    }

    private class RecordingMatrixSender : MatrixSender {
        val bodies: MutableList<String> = mutableListOf()
        private var counter = 0

        override suspend fun send(rendered: RenderedMatrix): MessageId {
            bodies += rendered.body
            return MessageId("m-${counter++}")
        }

        override suspend fun replace(messageId: MessageId, rendered: RenderedMatrix) {
            bodies += rendered.body
        }

        override suspend fun redact(messageId: MessageId) = Unit

        override suspend fun typing() = Unit

        override suspend fun react(messageId: MessageId, emoji: String) = Unit
    }
}
