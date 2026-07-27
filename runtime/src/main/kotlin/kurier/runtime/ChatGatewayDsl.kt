package kurier.runtime

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
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

    // The handler is the last resort for exceptions escaping jobs adapters launch into this scope
    // (invited by connect(scope)): without it they reach the thread's default uncaught handler,
    // which would kill the process on Android. Adapter-flow failures are attributed in start() instead.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("kurier-gateway") +
            CoroutineExceptionHandler { _, _ -> },
    )
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
            // UNDISPATCHED: SharedFlow.collect registers its subscriber before first suspending, so
            // subscription is synchronous within start() — an adapter emitting right after start()
            // returns can't lose the race (the SPI's subscription-timing contract depends on this).
            // The catch is defense in depth for adapters that break the never-throw rule — the
            // offending platform fails, the rest live.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                connection.messages.catch { markFailed(adapter.platform, it) }.collect { _messages.emit(it) }
            }
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                connection.events.catch { markFailed(adapter.platform, it) }.collect { _events.emit(it) }
            }
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                connection.state.catch { markFailed(adapter.platform, it) }.collect { state ->
                    _connections.update { current ->
                        // Failed is terminal (see ConnectionState): once a platform fails — reported
                        // by the adapter or set by the containment guards — later state reports
                        // can't resurrect it. Stop() stamps Closed, so a restart starts clean.
                        if (current[adapter.platform] is ConnectionState.Failed) {
                            current
                        } else {
                            current + (adapter.platform to state)
                        }
                    }
                }
            }
        }
    }

    override suspend fun stop() {
        active.values.forEach { it.close() }
        active.clear()
        scope.coroutineContext.cancelChildren()
        // Deterministic terminal state: close() drives each adapter to Closed, but cancelChildren()
        // races the collectors forwarding it — stamp the map so a stopped gateway never reads Connected.
        _connections.update { states -> states.mapValues { ConnectionState.Closed } }
    }

    private fun markFailed(platform: PlatformId, cause: Throwable) {
        _connections.update { it + (platform to ConnectionState.Failed(cause)) }
    }

    override fun channel(id: ChannelId): Channel? = active.values.firstNotNullOfOrNull { it.channel(id) }

    private companion object {
        const val BUFFER_CAPACITY = 64
    }
}
