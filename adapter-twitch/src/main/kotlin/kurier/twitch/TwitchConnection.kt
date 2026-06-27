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
import kotlinx.serialization.json.decodeFromJsonElement
import kurier.AdapterConnection
import kurier.ChannelEvent
import kurier.ConnectionState
import kurier.IncomingMessage
import kurier.PlatformId
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.seconds

/**
 * One live Twitch chat connection. On start it validates the token (→ the bot's user id) and resolves
 * the channel (→ broadcaster id), then runs the EventSub WebSocket: on `session_welcome` it creates
 * the `channel.chat.message` subscription, and forwards `notification`s into [messages]. Fatal config
 * errors (bad token, unknown channel) flip to [ConnectionState.Failed]; a dropped socket reconnects
 * with backoff. Robust keepalive/`session_reconnect` handling and token refresh land in TW-3.
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
                api.openEventSub { handleFrame(it, bot.userId, broadcaster.id) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                cause = failure
            }
            if (!coroutineContext.isActive) break
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

    private suspend fun handleFrame(text: String, botId: String, broadcasterId: String) {
        val message = json.decodeFromString<EventSubMessage>(text)
        when (message.metadata.messageType) {
            "session_welcome" -> {
                val sessionId = json.decodeFromJsonElement<WelcomePayload>(message.payload).session.id
                api.createSubscription(broadcasterId = broadcasterId, userId = botId, sessionId = sessionId)
                _state.value = ConnectionState.Connected
            }

            "notification" -> if (message.metadata.subscriptionType == TwitchApi.CHAT_MESSAGE_TYPE) {
                val event = json.decodeFromJsonElement<NotificationPayload>(message.payload).event
                if (event.chatterUserId != botId) { // the broadcast echoes the bot's own messages
                    _messages.emit(event.toIncomingMessage(api, platform, botId))
                }
            }
            // session_keepalive = healthy heartbeat; session_reconnect/revocation are hardened in TW-3.
        }
    }

    override suspend fun close() {
        job.cancelAndJoin()
        api.close()
        _state.value = ConnectionState.Closed
    }

    private companion object {
        val BACKOFF = 5.seconds
    }
}
