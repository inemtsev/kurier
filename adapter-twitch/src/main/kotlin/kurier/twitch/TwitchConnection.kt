package kurier.twitch

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
import kotlinx.serialization.json.Json
import kurier.AdapterConnection
import kurier.ChannelEvent
import kurier.ConnectionState
import kurier.IncomingMessage
import kurier.PlatformId
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One live Twitch chat connection. On start it validates the token (→ the bot's user id) and resolves
 * the channel (→ broadcaster id), then runs the EventSub WebSocket: on `session_welcome` it creates the
 * `channel.chat.message` subscription and forwards `notification`s into [messages]. A keepalive read
 * deadline detects a silently dropped socket; `session_reconnect` drops and reconnects fresh; a
 * `revocation` is fatal. Other fatal config errors (bad token, unknown channel) flip to
 * [ConnectionState.Failed]; an unexpectedly dropped socket reconnects with backoff. Token refresh
 * (using the OAuth refresh token) is still a TODO — see the adapter notes.
 */
internal class TwitchConnection(
    private val api: TwitchApi,
    private val platform: PlatformId,
    private val channel: String,
    scope: CoroutineScope,
) : AdapterConnection {

    private val _messages = MutableSharedFlow<IncomingMessage>()
    private val _events = MutableSharedFlow<ChannelEvent>()
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)

    override val messages: Flow<IncomingMessage> = _messages.asSharedFlow()
    override val events: Flow<ChannelEvent> = _events.asSharedFlow()
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    // The receive deadline for the next frame; widened to the session's window once `session_welcome` lands.
    private var keepalive: Duration = DEFAULT_KEEPALIVE + KEEPALIVE_MARGIN

    private val job: Job = scope.launch { run() }

    @Suppress("TooGenericExceptionCaught") // startup/socket failures must surface as state, not crash the scope
    private suspend fun run() {
        val bot = try {
            api.validate()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            _state.value = ConnectionState.Failed(failure)
            return
        }
        val broadcaster = resolveBroadcaster() ?: return

        while (coroutineContext.isActive) {
            var cause: Throwable? = null
            try {
                api.openEventSub(keepaliveTimeout = { keepalive }) { handleFrame(it, bot.userId, broadcaster.id) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                cause = failure
            }
            // Stop on cancellation or a revocation-induced Failed state; otherwise back off and reconnect.
            if (!coroutineContext.isActive || _state.value is ConnectionState.Failed) break
            _state.value = ConnectionState.Backoff(BACKOFF, cause)
            delay(BACKOFF)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun resolveBroadcaster(): TwitchUser? {
        val user = try {
            api.resolveBroadcaster(channel)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            _state.value = ConnectionState.Failed(failure)
            return null
        }
        if (user == null) {
            _state.value = ConnectionState.Failed(IllegalStateException("Twitch channel '$channel' not found"))
        }
        return user
    }

    /** Interprets one frame; returns false to drop the socket so [run] reconnects (or stops, if revoked). */
    private suspend fun handleFrame(text: String, botId: String, broadcasterId: String): Boolean =
        when (val action = parseFrame(json, text)) {
            is FrameAction.Welcome -> {
                keepalive = keepaliveTimeout(action.keepaliveSeconds)
                api.createSubscription(broadcasterId = broadcasterId, userId = botId, sessionId = action.sessionId)
                _state.value = ConnectionState.Connected
                true
            }

            is FrameAction.Notification -> {
                // The broadcast echoes the bot's own messages; skip them so the bot never reacts to itself.
                if (action.event.chatterUserId != botId) {
                    _messages.emit(action.event.toIncomingMessage(api, platform, botId))
                }
                true
            }

            FrameAction.Reconnect -> false // drop this socket; run() reconnects fresh

            is FrameAction.Revoked -> {
                _state.value = ConnectionState.Failed(
                    IllegalStateException("Twitch revoked the chat subscription: ${action.status}"),
                )
                false
            }

            FrameAction.Ignore -> true
        }

    /** Reconnect if no keepalive or notification lands within the session's window plus slack for jitter. */
    private fun keepaliveTimeout(seconds: Int?): Duration = (seconds?.seconds ?: DEFAULT_KEEPALIVE) + KEEPALIVE_MARGIN

    override suspend fun close() {
        job.cancelAndJoin()
        api.close()
        _state.value = ConnectionState.Closed
    }

    private companion object {
        val BACKOFF = 5.seconds

        // Twitch's default keepalive_timeout_seconds; the welcome frame may override it per session.
        val DEFAULT_KEEPALIVE = 10.seconds
        val KEEPALIVE_MARGIN = 5.seconds
    }
}
