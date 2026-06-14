package kurier.discord

import kotlinx.coroutines.CoroutineScope
import kurier.AdapterConnection
import kurier.ChannelAdapter
import kurier.PlatformId

/**
 * Discord adapter wrapping Kord.
 *
 * @param id Platform id for this instance; must be unique within a gateway.
 *   Override it to run several Discord bots side by side.
 */
public class DiscordAdapter(
    @Suppress("unused", "UnusedPrivateProperty") private val token: String,
    id: String = "discord",
) : ChannelAdapter {

    override val platform: PlatformId = PlatformId(id)

    override fun connect(scope: CoroutineScope): AdapterConnection {
        TODO("M2: Kord gateway connection, message normalization, edit-throttled streaming")
    }
}
