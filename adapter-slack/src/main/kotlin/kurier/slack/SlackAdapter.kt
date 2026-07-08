package kurier.slack

import kotlinx.coroutines.CoroutineScope
import kurier.AdapterConnection
import kurier.ChannelAdapter
import kurier.PlatformId

/**
 * Slack adapter wrapping the official `slack-api-client`: Events API inbound over Socket Mode
 * (no webhook server) and the Web API outbound. The Socket Mode client runs on the Java-WebSocket
 * backend — the SDK's default (Tyrus, `javax.websocket`) is not Android-viable.
 *
 * Slack apps need two tokens: the bot token (`xoxb-…`) authorizes Web API calls and the app-level
 * token (`xapp-…`, with the `connections:write` scope) opens the Socket Mode connection.
 *
 * Android note: the SDK deserializes with gson (reflection), so R8/ProGuard builds need keep rules
 * for `com.slack.api.**` model classes.
 *
 * @param id Platform id for this instance; must be unique within a gateway.
 *   Override it to run several Slack apps side by side.
 */
public class SlackAdapter(
    private val botToken: String,
    private val appToken: String,
    id: String = "slack",
) : ChannelAdapter {

    init {
        require(botToken.isNotBlank()) { "Slack bot token must not be blank" }
        require(appToken.isNotBlank()) { "Slack app-level token must not be blank" }
    }

    override val platform: PlatformId = PlatformId(id)

    override fun connect(scope: CoroutineScope): AdapterConnection =
        SlackConnection(botToken = botToken, appToken = appToken, platform = platform, scope = scope)
}
