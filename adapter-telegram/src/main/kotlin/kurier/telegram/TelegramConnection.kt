package kurier.telegram

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kurier.AdapterConnection
import kurier.ChannelEvent
import kurier.ConnectionState
import kurier.IncomingMessage
import kurier.PlatformId
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.seconds

/**
 * One live Telegram bot connection. On start it handshakes via `getMe` — fast
 * [ConnectionState.Connected], or [ConnectionState.Failed] on a rejected token —
 * and resolves the bot identity, then runs a long-polling `getUpdates` loop that
 * normalizes updates into [messages]. Owns reconnection and backoff (SPI rule #4):
 * a transient failure flips [state] to [ConnectionState.Backoff] and retries; a fatal
 * Bot API error flips it to [ConnectionState.Failed] and stops — retrying is pointless.
 */
internal class TelegramConnection(
    private val api: TelegramApi,
    private val platform: PlatformId,
    scope: CoroutineScope,
) : AdapterConnection {

    // Rendezvous like FakeAdapter: the gateway forwards into its own buffered flow,
    // so emit here couples only to a fast hop, never to a slow downstream subscriber.
    private val _messages = MutableSharedFlow<IncomingMessage>()
    private val _events = MutableSharedFlow<ChannelEvent>()
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)

    override val messages: Flow<IncomingMessage> = _messages.asSharedFlow()
    override val events: Flow<ChannelEvent> = _events.asSharedFlow()
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val job: Job = scope.launch { connectAndPoll() }

    private suspend fun connectAndPoll() {
        val bot = handshake() ?: return // token permanently rejected — stay Failed
        pollLoop(bot)
    }

    /** Validates the token via `getMe` and resolves the bot identity; retries transient failures. */
    @Suppress("TooGenericExceptionCaught") // the handshake must survive any transient network/parse failure
    private suspend fun handshake(): User? {
        while (coroutineContext.isActive) {
            try {
                val bot = api.getMe()
                _state.value = ConnectionState.Connected
                return bot
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                if (handleFailure(failure)) break // fatal — leave the loop and report null
            }
        }
        return null
    }

    @Suppress("TooGenericExceptionCaught") // the loop must survive any transient network/parse failure
    private suspend fun pollLoop(bot: User) {
        var offset = INITIAL_OFFSET
        while (coroutineContext.isActive) {
            try {
                val updates = api.getUpdates(offset, POLL_TIMEOUT_SECONDS)
                _state.value = ConnectionState.Connected
                for (update in updates) {
                    offset = update.updateId + 1
                    val message = update.message ?: continue
                    _messages.emit(message.toIncomingMessage(platform, api, bot))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                if (handleFailure(failure)) return
            }
        }
    }

    /** Routes a failure to a state transition; returns true if it is fatal and the caller should stop. */
    private suspend fun handleFailure(failure: Exception): Boolean {
        if (failure is TelegramApiException && failure.isFatal) {
            _state.value = ConnectionState.Failed(failure)
            return true
        }
        _state.value = ConnectionState.Backoff(BACKOFF, failure)
        delay(BACKOFF)
        return false
    }

    override suspend fun close() {
        job.cancelAndJoin()
        api.close()
        _state.value = ConnectionState.Closed
    }

    private companion object {
        const val INITIAL_OFFSET = 0L
        const val POLL_TIMEOUT_SECONDS = 30
        val BACKOFF = 5.seconds
    }
}
