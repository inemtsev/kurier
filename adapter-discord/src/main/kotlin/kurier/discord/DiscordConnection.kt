package kurier.discord

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.event.Event
import dev.kord.core.event.gateway.DisconnectEvent
import dev.kord.core.event.gateway.ReadyEvent
import dev.kord.core.event.gateway.ResumedEvent
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.core.event.message.MessageDeleteEvent
import dev.kord.core.event.message.ReactionAddEvent
import dev.kord.core.event.message.ReactionRemoveEvent
import dev.kord.gateway.Intent
import dev.kord.gateway.Intents
import dev.kord.gateway.PrivilegedIntent
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
import kurier.Author
import kurier.Channel
import kurier.ChannelEvent
import kurier.ChannelEvent.MessageDeleted
import kurier.ChannelEvent.ReactionAdded
import kurier.ChannelEvent.ReactionRemoved
import kurier.ChannelId
import kurier.ChannelKind
import kurier.ConnectionState
import kurier.IncomingMessage
import kurier.MessageId
import kurier.PlatformId

/**
 * One live Discord bot connection. Unlike Telegram (which owns its poll loop and backoff), Kord
 * owns the gateway and reconnection — so this connection *observes* Kord's lifecycle events and
 * maps them onto [ConnectionState], and forwards normalized [MessageCreateEvent]s into [messages].
 *
 * Kord's factory is suspend but the SPI's `connect` is not, so the client is built lazily inside
 * this connection's own coroutine; until then the connection sits in [ConnectionState.Connecting].
 */
internal class DiscordConnection(
    private val token: String,
    private val platform: PlatformId,
    scope: CoroutineScope,
) : AdapterConnection {

    // Rendezvous like Telegram: the gateway forwards into its own buffered flow.
    private val _messages = MutableSharedFlow<IncomingMessage>()
    private val _events = MutableSharedFlow<ChannelEvent>()
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)

    override val messages: Flow<IncomingMessage> = _messages.asSharedFlow()
    override val events: Flow<ChannelEvent> = _events.asSharedFlow()
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    @Volatile
    private var kord: Kord? = null
    private val job: Job = scope.launch { run() }

    @Suppress("TooGenericExceptionCaught") // building the client must report any failure as Failed, not crash the scope
    private suspend fun run() {
        val client = try {
            Kord(token)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            _state.value = ConnectionState.Failed(failure)
            return
        }
        kord = client
        coroutineScope {
            // Collect before login so the ReadyEvent isn't missed.
            launch { client.events.collect { handle(it, client) } }
            client.login { intents = gatewayIntents() }
        }
    }

    private suspend fun handle(event: Event, client: Kord) {
        when (event) {
            is ReadyEvent, is ResumedEvent -> _state.value = ConnectionState.Connected
            // Kord owns reconnection; distinguishing a fatal auth-close (4004) is a later refinement.
            is DisconnectEvent -> _state.value = ConnectionState.Connecting
            is MessageCreateEvent -> onMessage(event, client)
            is MessageDeleteEvent -> _events.emit(MessageDeleted(toChannelId(event.channelId), toMessageId(event.messageId)))
            is ReactionAddEvent -> onReaction(event.reactionInfo(), client.selfId, ::ReactionAdded)
            is ReactionRemoveEvent -> onReaction(event.reactionInfo(), client.selfId, ::ReactionRemoved)
        }
    }

    private suspend fun onMessage(event: MessageCreateEvent, client: Kord) {
        if (event.message.author?.id == client.selfId) return // drop the bot's own messages
        _messages.emit(event.toIncomingMessage(platform, client.selfId, client))
    }

    private suspend fun onReaction(
        info: ReactionInfo,
        selfId: Snowflake,
        build: (ChannelId, MessageId, String, Author) -> ChannelEvent,
    ) {
        reactionEvent(platform, selfId, info, build)?.let { _events.emit(it) }
    }

    private fun toChannelId(snowflake: Snowflake): ChannelId = ChannelId("${platform.value}:$snowflake")
    private fun toMessageId(snowflake: Snowflake): MessageId = MessageId(snowflake.toString())

    override fun channel(id: ChannelId): Channel? {
        val client = kord ?: return null
        // Proactive send: kind isn't known without a fetch, so default to GROUP (send needs only id + kord).
        return id.toSnowflake()?.let { DiscordChannel(KordDiscordSender(client, it), id, platform, ChannelKind.GROUP, name = null) }
    }

    private fun ChannelId.toSnowflake(): Snowflake? {
        if (value.substringBefore(':') != platform.value) return null
        return value.substringAfter(':').toULongOrNull()?.let { Snowflake(it) }
    }

    override suspend fun close() {
        job.cancelAndJoin()
        kord?.shutdown()
        _state.value = ConnectionState.Closed
    }

    @OptIn(PrivilegedIntent::class)
    private fun gatewayIntents(): Intents = Intents {
        +Intent.GuildMessages
        +Intent.DirectMessages
        +Intent.GuildMessageReactions
        +Intent.DirectMessagesReactions
        // Privileged: must be enabled in Discord's dev portal, or message content is empty in guilds.
        +Intent.MessageContent
    }
}

/** The wire fields of a reaction event, decoupled from Kord's two distinct reaction event types. */
internal data class ReactionInfo(
    val userId: Snowflake,
    val channelId: Snowflake,
    val messageId: Snowflake,
    val emoji: String,
)

private fun ReactionAddEvent.reactionInfo(): ReactionInfo = ReactionInfo(userId, channelId, messageId, emoji.name)
private fun ReactionRemoveEvent.reactionInfo(): ReactionInfo = ReactionInfo(userId, channelId, messageId, emoji.name)

/**
 * Builds the [ChannelEvent] for a reaction, or null when it is the bot's own reaction (Discord echoes
 * those back and we ignore them). Pure, so the mapping and self-filter are unit-testable without a gateway.
 */
internal fun reactionEvent(
    platform: PlatformId,
    selfId: Snowflake,
    info: ReactionInfo,
    build: (ChannelId, MessageId, String, Author) -> ChannelEvent,
): ChannelEvent? {
    if (info.userId == selfId) return null
    return build(
        ChannelId("${platform.value}:${info.channelId}"),
        MessageId(info.messageId.toString()),
        info.emoji,
        Author(info.userId.toString()),
    )
}
