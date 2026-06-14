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
 * One live Telegram bot connection: a long-polling `getUpdates` loop that
 * normalizes updates into [messages]. Owns reconnection and backoff (SPI rule #4):
 * a transient failure flips [state] to [ConnectionState.Backoff] and retries, while
 * a fatal Bot API error (bad token / forbidden) flips it to [ConnectionState.Failed]
 * and stops the loop — retrying an auth failure is pointless.
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

    private val job: Job = scope.launch { poll() }

    @Suppress("TooGenericExceptionCaught") // the loop must survive any transient network/parse failure
    private suspend fun poll() {
        var offset = INITIAL_OFFSET
        while (coroutineContext.isActive) {
            try {
                val updates = api.getUpdates(offset, POLL_TIMEOUT_SECONDS)
                _state.value = ConnectionState.Connected
                for (update in updates) {
                    offset = update.updateId + 1
                    val message = update.message ?: continue
                    _messages.emit(message.toIncomingMessage(platform))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (apiError: TelegramApiException) {
                if (apiError.isFatal) {
                    _state.value = ConnectionState.Failed(apiError)
                    return // token permanently rejected — stop, don't spin
                }
                backOff(apiError)
            } catch (failure: Exception) {
                backOff(failure)
            }
        }
    }

    private suspend fun backOff(cause: Throwable) {
        _state.value = ConnectionState.Backoff(BACKOFF, cause)
        delay(BACKOFF)
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
