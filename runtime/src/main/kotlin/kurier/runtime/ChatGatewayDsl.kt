package kurier.runtime

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kurier.AdapterConnection
import kurier.Channel
import kurier.ChannelAdapter
import kurier.ChannelEvent
import kurier.ChannelId
import kurier.ChatGateway
import kurier.ConnectionState
import kurier.IncomingMessage
import kurier.PlatformId

public fun chatGateway(block: GatewayBuilder.() -> Unit): ChatGateway = GatewayBuilder().apply(block).build()

public class GatewayBuilder internal constructor() {
    private val adapters = mutableListOf<ChannelAdapter>()

    public fun install(adapter: ChannelAdapter) {
        adapters += adapter
    }

    internal fun build(): ChatGateway {
        val duplicates = adapters.groupBy { it.platform }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) {
            "Multiple adapters share platform id(s) $duplicates — give each instance a unique id"
        }
        return DefaultChatGateway(adapters.toList())
    }
}

internal class DefaultChatGateway(private val adapters: List<ChannelAdapter>) : ChatGateway {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("kurier-gateway"))
    private val active = LinkedHashMap<PlatformId, AdapterConnection>()

    // Bounded slack so one slow subscriber doesn't lockstep-couple all platforms;
    // on sustained overload emit suspends (lossless) rather than dropping messages.
    private val _messages = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = BUFFER_CAPACITY)
    private val _events = MutableSharedFlow<ChannelEvent>(extraBufferCapacity = BUFFER_CAPACITY)
    private val _connections = MutableStateFlow<Map<PlatformId, ConnectionState>>(emptyMap())

    override val messages: Flow<IncomingMessage> = _messages.asSharedFlow()
    override val events: Flow<ChannelEvent> = _events.asSharedFlow()
    override val connections: StateFlow<Map<PlatformId, ConnectionState>> = _connections.asStateFlow()

    override suspend fun start() {
        check(active.isEmpty()) { "Gateway already started" }
        for (adapter in adapters) {
            val connection = adapter.connect(scope)
            active[adapter.platform] = connection
            scope.launch { connection.messages.collect { _messages.emit(it) } }
            scope.launch { connection.events.collect { _events.emit(it) } }
            scope.launch {
                connection.state.collect { state ->
                    _connections.update { it + (adapter.platform to state) }
                }
            }
        }
    }

    override suspend fun stop() {
        active.values.forEach { it.close() }
        active.clear()
        scope.coroutineContext.cancelChildren()
    }

    override fun channel(id: ChannelId): Channel? = active.values.firstNotNullOfOrNull { it.channel(id) }

    private companion object {
        const val BUFFER_CAPACITY = 64
    }
}
