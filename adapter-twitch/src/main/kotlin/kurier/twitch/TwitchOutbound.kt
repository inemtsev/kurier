package kurier.twitch

import kotlinx.coroutines.CancellationException
import kurier.KurierException
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
    @Suppress("TooGenericExceptionCaught") // transport failures must honor the KurierException contract
    suspend fun send(text: String): MessageId = try {
        MessageId(api.sendMessage(broadcasterId = broadcasterId, senderId = senderId, message = text))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: KurierException) {
        throw failure
    } catch (failure: Exception) {
        throw KurierException("Twitch chat send transport failure", cause = failure, retryable = true)
    }
}
