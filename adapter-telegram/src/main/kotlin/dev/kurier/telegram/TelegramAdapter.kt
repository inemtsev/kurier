package dev.kurier.telegram

import dev.kurier.AdapterConnection
import dev.kurier.ChannelAdapter
import dev.kurier.PlatformId
import kotlinx.coroutines.CoroutineScope

/**
 * Telegram Bot API adapter. Implemented directly on the Ktor client —
 * the Bot API is simple enough that a heavyweight SDK isn't warranted.
 *
 * @param id Platform id for this instance; must be unique within a gateway.
 *   Override it to run several Telegram bots side by side, e.g.
 *   `TelegramAdapter(supportToken, id = "telegram-support")`.
 */
public class TelegramAdapter(
    @Suppress("unused", "UnusedPrivateProperty") private val token: String,
    id: String = "telegram",
) : ChannelAdapter {

    override val platform: PlatformId = PlatformId(id)

    override fun connect(scope: CoroutineScope): AdapterConnection {
        TODO("M1: long-polling getUpdates loop, message normalization, MarkdownV2 rendering")
    }
}
