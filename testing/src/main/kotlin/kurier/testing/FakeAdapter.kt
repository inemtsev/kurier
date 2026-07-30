package kurier.testing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kurier.AdapterConnection
import kurier.Attachment
import kurier.Author
import kurier.Capability
import kurier.Channel
import kurier.ChannelAdapter
import kurier.ChannelEvent
import kurier.ChannelId
import kurier.ChannelKind
import kurier.ConnectionState
import kurier.Content
import kurier.IncomingMessage
import kurier.MessageId
import kurier.MessageRef
import kurier.PlatformId
import kurier.RichText
import kurier.SentMessage
import kurier.StreamingOptions
import kurier.UserId
import kurier.nativeId

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

    private val _sent = mutableListOf<Content>()

    /**
     * Snapshot of everything sent through any channel of this adapter, in order.
     * Records sends only; recorded edits arrive in a later release.
     */
    public val sent: List<Content> get() = synchronized(_sent) { _sent.toList() }

    public fun channel(id: String = "general", kind: ChannelKind = ChannelKind.GROUP): FakeChannel {
        val channelId = ChannelId.of(platform, id)
        return channels.getOrPut(channelId) {
            FakeChannel(channelId, platform, kind) { cid, content ->
                synchronized(_sent) { _sent += content }
                onSend(cid, content)
            }
        }
    }

    /** Emits an incoming message; suspends until a gateway is subscribed. */
    public suspend fun receive(
        text: String,
        channel: FakeChannel = channel(),
        from: Author = Author(UserId("user-1"), "Test User"),
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

            // Mints on demand, honoring the contract: any well-formed own-platform id resolves.
            override fun channel(id: ChannelId): Channel? =
                id.nativeId(platform)?.let { native -> this@FakeAdapter.channel(native) }
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
        // Not recorded: streaming tests observe the final text via FakeAdapter.sent, not the edit sequence.
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
