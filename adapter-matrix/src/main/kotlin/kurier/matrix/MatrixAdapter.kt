package kurier.matrix

import kotlinx.coroutines.CoroutineScope
import kurier.AdapterConnection
import kurier.ChannelAdapter
import kurier.PlatformId

/**
 * Matrix adapter wrapping Trixnity's low-level client-server API client. [connect] runs a `/sync`
 * long-poll loop scoped to the gateway — no webhook server, no local store. Authenticates with an
 * access token (the bot pattern); end-to-end-encrypted rooms are out of scope (their messages don't
 * surface) until a future slice adopts Trixnity's high-level crypto client.
 *
 * @param homeserver Base URL of the bot's homeserver, e.g. `https://matrix.org`.
 * @param id Platform id for this instance; must be unique within a gateway.
 */
public class MatrixAdapter(
    private val homeserver: String,
    private val accessToken: String,
    id: String = "matrix",
) : ChannelAdapter {

    init {
        require(homeserver.isNotBlank()) { "Matrix homeserver URL must not be blank" }
        require(accessToken.isNotBlank()) { "Matrix access token must not be blank" }
    }

    override val platform: PlatformId = PlatformId(id)

    override fun connect(scope: CoroutineScope): AdapterConnection =
        MatrixConnection(homeserver = homeserver, accessToken = accessToken, platform = platform, scope = scope)
}
