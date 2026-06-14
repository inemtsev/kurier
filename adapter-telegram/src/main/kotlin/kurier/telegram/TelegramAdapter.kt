package kurier.telegram

import kotlinx.coroutines.CoroutineScope
import kurier.AdapterConnection
import kurier.ChannelAdapter
import kurier.PlatformId

/**
 * Telegram Bot API adapter. Implemented directly on the Ktor client —
 * the Bot API is simple enough that a heavyweight SDK isn't warranted.
 *
 * [connect] starts a long-polling `getUpdates` loop scoped to the gateway; the
 * connection owns its own HTTP client, reconnection, and backoff.
 *
 * @param id Platform id for this instance; must be unique within a gateway.
 *   Override it to run several Telegram bots side by side, e.g.
 *   `TelegramAdapter(supportToken, id = "telegram-support")`.
 */
public class TelegramAdapter(
    private val token: String,
    id: String = "telegram",
) : ChannelAdapter {

    init {
        require(token.isNotBlank()) { "Telegram bot token must not be blank" }
    }

    override val platform: PlatformId = PlatformId(id)

    override fun connect(scope: CoroutineScope): AdapterConnection =
        TelegramConnection(api = TelegramApi(token), platform = platform, scope = scope)
}
