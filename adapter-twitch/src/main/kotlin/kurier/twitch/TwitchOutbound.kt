package kurier.twitch

import kurier.MessageId

/**
 * The outbound half of a Twitch chat connection: posts messages as the authenticated bot ([senderId])
 * into one broadcaster's chat ([broadcasterId]). Bundled so a [TwitchChannel] carries a single send
 * collaborator instead of the API plus both routing ids.
 */
internal class TwitchOutbound(
    private val api: TwitchApi,
    private val broadcasterId: String,
    private val senderId: String,
) {
    suspend fun send(text: String): MessageId =
        MessageId(api.sendMessage(broadcasterId = broadcasterId, senderId = senderId, message = text))
}
