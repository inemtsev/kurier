package dev.kurier.testing

import dev.kurier.AdapterConnection
import dev.kurier.Attachment
import dev.kurier.Author
import dev.kurier.Capability
import dev.kurier.Channel
import dev.kurier.ChannelAdapter
import dev.kurier.ChannelEvent
import dev.kurier.ChannelId
import dev.kurier.ChannelKind
import dev.kurier.ConnectionState
import dev.kurier.Content
import dev.kurier.IncomingMessage
import dev.kurier.MessageId
import dev.kurier.MessageRef
import dev.kurier.PlatformId
import dev.kurier.RichText
import dev.kurier.SentMessage
import dev.kurier.StreamingOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * In-memory [ChannelAdapter] for unit-testing bots and agents — no network,
 * no tokens. Drive it with [receive]; observe replies via [sent] or [onSend].
 */
public class FakeAdapter(
    id: String = "fake",
    private val onSend: (ChannelId, Content) -> Unit = { _, _ -> },
) : ChannelAdapter {

    override val platform: PlatformId = PlatformId(id)

    private val incoming = MutableSharedFlow<IncomingMessage>()
    private val incomingEvents = MutableSharedFlow<ChannelEvent>()
    private val state = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)
    private val channels = LinkedHashMap<ChannelId, FakeChannel>()
    private var counter = 0

    /** Everything sent through any channel of this adapter, in order. */
    public val sent: MutableList<Content> = mutableListOf()

    public fun channel(id: String = "general", kind: ChannelKind = ChannelKind.GROUP): FakeChannel {
        val channelId = ChannelId("${platform.value}:$id")
        return channels.getOrPut(channelId) {
            FakeChannel(channelId, platform, kind) { cid, content ->
                sent += content
                onSend(cid, content)
            }
        }
    }

    /** Emits an incoming message; suspends until a gateway is subscribed. */
    public suspend fun receive(
        text: String,
        channel: FakeChannel = channel(),
        from: Author = Author("user-1", "Test User"),
        directedAtBot: Boolean = true,
    ) {
        incoming.subscriptionCount.first { it > 0 }
        incoming.emit(
            FakeIncomingMessage(
                id = MessageId("fake-${counter++}"),
                channel = channel,
                author = from,
                rich = RichText.plain(text),
                isDirectedAtBot = directedAtBot,
            ),
        )
    }

    override fun connect(scope: CoroutineScope): AdapterConnection {
        state.value = ConnectionState.Connected
        return object : AdapterConnection {
            override val messages: Flow<IncomingMessage> = incoming.asSharedFlow()
            override val events: Flow<ChannelEvent> = incomingEvents.asSharedFlow()
            override val state: StateFlow<ConnectionState> = this@FakeAdapter.state.asStateFlow()
            override fun channel(id: ChannelId): Channel? = channels[id]
            override suspend fun close() {
                this@FakeAdapter.state.value = ConnectionState.Closed
            }
        }
    }
}

public class FakeChannel internal constructor(
    override val id: ChannelId,
    override val platform: PlatformId,
    override val kind: ChannelKind,
    private val onSend: (ChannelId, Content) -> Unit,
) : Channel {

    override val name: String? = null
    private var counter = 0

    override fun supports(capability: Capability): Boolean = true

    override suspend fun send(content: Content): SentMessage {
        onSend(id, content)
        return FakeSentMessage(MessageId("sent-${counter++}"), id)
    }

    override suspend fun sendStreaming(tokens: Flow<String>, options: StreamingOptions): SentMessage {
        val full = StringBuilder()
        tokens.collect { full.append(it) }
        return send(Content.text(full.toString()))
    }
}

private class FakeSentMessage(override val id: MessageId, override val channelId: ChannelId) : SentMessage {
    override suspend fun edit(content: Content) {
        // recorded edits land in M2 alongside streaming assertions
    }

    override suspend fun delete() {
        // no-op: deletion tracking not needed yet
    }
}

private class FakeIncomingMessage(
    override val id: MessageId,
    override val channel: Channel,
    override val author: Author,
    override val rich: RichText,
    override val isDirectedAtBot: Boolean,
) : IncomingMessage {
    override val attachments: List<Attachment> = emptyList()
    override val replyTo: MessageRef? = null
    override val raw: Any? = null
}
