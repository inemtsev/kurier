package kurier.matrix

import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kurier.AdapterConnection
import kurier.Channel
import kurier.ChannelEvent
import kurier.ChannelId
import kurier.ChannelKind
import kurier.ConnectionState
import kurier.IncomingMessage
import kurier.PlatformId
import net.folivo.trixnity.clientserverapi.client.MatrixAuthProvider
import net.folivo.trixnity.clientserverapi.client.MatrixClientServerApiClientImpl
import net.folivo.trixnity.clientserverapi.client.SyncState
import net.folivo.trixnity.clientserverapi.client.classicInMemory
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.events.ClientEvent
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent
import net.folivo.trixnity.core.subscribeContent
import kotlin.time.Duration.Companion.seconds

/**
 * One live Matrix connection over Trixnity's low-level client. Like Telegram it long-polls (`/sync`),
 * but Trixnity owns the loop and `next_batch` token — so this connection subscribes to room messages,
 * mirrors Trixnity's [SyncState] onto [ConnectionState], and starts the sync within the gateway scope.
 * Unencrypted rooms only: E2E messages arrive as undecryptable events and never reach [messages].
 */
internal class MatrixConnection(
    homeserver: String,
    accessToken: String,
    private val platform: PlatformId,
    scope: CoroutineScope,
) : AdapterConnection {

    private val _messages = MutableSharedFlow<IncomingMessage>()
    private val _events = MutableSharedFlow<ChannelEvent>()
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)

    override val messages: Flow<IncomingMessage> = _messages.asSharedFlow()
    override val events: Flow<ChannelEvent> = _events.asSharedFlow()
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val client = MatrixClientServerApiClientImpl(
        baseUrl = Url(homeserver),
        authProvider = MatrixAuthProvider.classicInMemory(accessToken),
        coroutineContext = scope.coroutineContext,
    )

    @Volatile
    private var session: MatrixSession? = null
    private val job: Job = scope.launch { run() }

    @Suppress("TooGenericExceptionCaught") // a bad token / unreachable homeserver must surface as Failed, not crash
    private suspend fun run() = coroutineScope {
        val self = try {
            client.authentication.whoAmI().getOrThrow().userId
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            _state.value = ConnectionState.Failed(failure)
            return@coroutineScope
        }
        val activeSession = MatrixSession(client, self)
        session = activeSession
        client.sync.subscribeContent<RoomMessageEventContent.TextBased.Text> { event ->
            if (event is ClientEvent.RoomEvent && event.sender != activeSession.self) {
                _messages.emit(event.toIncomingMessage(platform, activeSession))
            }
        }
        launch { client.sync.currentSyncState.collect { _state.value = it.toConnectionState() } }
        client.sync.start()
    }

    override fun channel(id: ChannelId): Channel? {
        val session = session ?: return null
        return roomIdOf(id, platform)?.let {
            MatrixChannel(TrixnityMatrixSender(session, it), id, platform, ChannelKind.GROUP, name = null)
        }
    }

    override suspend fun close() {
        job.cancelAndJoin()
        client.close()
        _state.value = ConnectionState.Closed
    }
}

/** Maps Trixnity's sync lifecycle onto kurier's [ConnectionState]. */
internal fun SyncState.toConnectionState(): ConnectionState = when (this) {
    SyncState.RUNNING, SyncState.TIMEOUT -> ConnectionState.Connected
    SyncState.INITIAL_SYNC, SyncState.STARTED -> ConnectionState.Connecting
    // Trixnity owns the retry timing; the delay here is just a hint for state observers.
    SyncState.ERROR -> ConnectionState.Backoff(SYNC_RETRY_HINT)
    SyncState.STOPPED -> ConnectionState.Closed
}

/** Parses a kurier [ChannelId] (`"<platform>:<roomId>"`) back to a Matrix [RoomId]; null if the prefix mismatches. */
internal fun roomIdOf(channelId: ChannelId, platform: PlatformId): RoomId? {
    if (channelId.value.substringBefore(':') != platform.value) return null
    return RoomId(channelId.value.substringAfter(':'))
}

private val SYNC_RETRY_HINT = 5.seconds
