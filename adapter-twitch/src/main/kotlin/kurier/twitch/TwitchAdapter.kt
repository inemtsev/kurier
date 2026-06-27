package kurier.twitch

import kotlinx.coroutines.CoroutineScope
import kurier.AdapterConnection
import kurier.ChannelAdapter
import kurier.PlatformId

/**
 * Twitch chat adapter, implemented directly on the Ktor client (EventSub WebSocket in, Helix REST out)
 * rather than wrapping Twitch4J — Twitch's chat surface is small enough to talk to its official API
 * directly, keeping the adapter thin and Android-safe (Twitch4J pulls Hystrix/Jackson/java.time).
 *
 * @param clientId The application's client id (registered at dev.twitch.tv).
 * @param accessToken A user OAuth token with `user:read:chat` + `user:write:chat` scopes.
 * @param channel The broadcaster login whose chat the bot reads and posts in.
 * @param id Platform id for this instance; must be unique within a gateway.
 */
public class TwitchAdapter(
    private val clientId: String,
    private val accessToken: String,
    private val channel: String,
    id: String = "twitch",
) : ChannelAdapter {

    init {
        require(clientId.isNotBlank()) { "Twitch client id must not be blank" }
        require(accessToken.isNotBlank()) { "Twitch access token must not be blank" }
        require(channel.isNotBlank()) { "Twitch channel must not be blank" }
    }

    override val platform: PlatformId = PlatformId(id)

    override fun connect(scope: CoroutineScope): AdapterConnection =
        TwitchConnection(
            api = TwitchApi(clientId = clientId, accessToken = accessToken),
            platform = platform,
            channel = channel,
            scope = scope,
        )
}
