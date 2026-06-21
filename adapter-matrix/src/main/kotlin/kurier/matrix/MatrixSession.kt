package kurier.matrix

import net.folivo.trixnity.clientserverapi.client.MatrixClientServerApiClient
import net.folivo.trixnity.core.model.UserId

/**
 * The bot's authenticated Matrix client plus its own user id. The id is needed to set typing
 * (the API records *who* is typing) and to filter the bot's own echoed messages.
 */
internal data class MatrixSession(
    val client: MatrixClientServerApiClient,
    val self: UserId,
)
