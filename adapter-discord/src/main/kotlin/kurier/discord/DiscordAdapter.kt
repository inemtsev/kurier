package kurier.discord

import kotlinx.coroutines.CoroutineScope
import kurier.AdapterConnection
import kurier.ChannelAdapter
import kurier.PlatformId

/**
 * Discord adapter wrapping Kord. [connect] starts a Kord gateway connection scoped to the gateway;
 * Kord owns its own reconnection, so [DiscordConnection] observes its lifecycle rather than retrying.
 *
 * @param id Platform id for this instance; must be unique within a gateway.
 *   Override it to run several Discord bots side by side.
 */
public class DiscordAdapter(
    private val token: String,
    id: String = "discord",
) : ChannelAdapter {

    init {
        require(token.isNotBlank()) { "Discord bot token must not be blank" }
    }

    override val platform: PlatformId = PlatformId(id)

    override fun connect(scope: CoroutineScope): AdapterConnection =
        DiscordConnection(token = token, platform = platform, scope = scope)
}
