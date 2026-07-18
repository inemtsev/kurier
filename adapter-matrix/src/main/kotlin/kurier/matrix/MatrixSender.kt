package kurier.matrix

import kurier.KurierException
import kurier.MessageId
import net.folivo.trixnity.core.MatrixServerException
import net.folivo.trixnity.core.model.EventId
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.events.m.ReactionEventContent
import net.folivo.trixnity.core.model.events.m.RelatesTo
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/**
 * The outbound Matrix operations a [MatrixChannel] needs, abstracted from the Trixnity client. The live
 * client can't be built without a homeserver, so this seam lets the channel's send/edit/stream logic be
 * unit-tested with a fake. It speaks kurier [MessageId] + [RenderedMatrix]; [TrixnityMatrixSender] maps
 * those onto Trixnity events.
 */
internal interface MatrixSender {
    suspend fun send(rendered: RenderedMatrix): MessageId
    suspend fun replace(messageId: MessageId, rendered: RenderedMatrix)
    suspend fun redact(messageId: MessageId)
    suspend fun typing()
    suspend fun react(messageId: MessageId, emoji: String)
}

/** The real [MatrixSender], backed by the Trixnity client against one room. */
internal class TrixnityMatrixSender(
    private val session: MatrixSession,
    private val roomId: RoomId,
) : MatrixSender {

    override suspend fun send(rendered: RenderedMatrix): MessageId =
        call("sendMessageEvent") { MessageId(session.client.room.sendMessageEvent(roomId, rendered.toText()).getOrThrow().full) }

    override suspend fun replace(messageId: MessageId, rendered: RenderedMatrix) {
        val target = EventId(messageId.value)
        // m.replace carries the new content plus a "* …" fallback for clients that don't render edits.
        val replacement = RoomMessageEventContent.TextBased.Text(
            body = "* ${rendered.body}",
            format = rendered.formattedBody?.let { HTML_FORMAT },
            formattedBody = rendered.formattedBody?.let { "* $it" },
            relatesTo = RelatesTo.Replace(target, rendered.toText()),
        )
        call("sendMessageEvent") { session.client.room.sendMessageEvent(roomId, replacement).getOrThrow() }
    }

    override suspend fun redact(messageId: MessageId) {
        call("redactEvent") { session.client.room.redactEvent(roomId, EventId(messageId.value)).getOrThrow() }
    }

    override suspend fun typing() {
        call("setTyping") {
            session.client.room.setTyping(roomId, session.self, typing = true, timeout = TYPING_TIMEOUT_MS).getOrThrow()
        }
    }

    override suspend fun react(messageId: MessageId, emoji: String) {
        val reaction = ReactionEventContent(RelatesTo.Annotation(EventId(messageId.value), key = emoji))
        call("sendMessageEvent") { session.client.room.sendMessageEvent(roomId, reaction).getOrThrow() }
    }

    /** Maps homeserver and transport failures onto the [KurierException] contract; `429`/5xx are retryable. */
    private suspend fun <T> call(method: String, block: suspend () -> T): T = try {
        block()
    } catch (failure: MatrixServerException) {
        val code = failure.statusCode.value
        throw MatrixApiException(method, failure, retryable = code == TOO_MANY_REQUESTS || code >= SERVER_ERROR)
    } catch (failure: IOException) {
        throw MatrixApiException(method, failure, retryable = true)
    }

    private companion object {
        val TYPING_TIMEOUT_MS: Long = 15.seconds.inWholeMilliseconds
        const val TOO_MANY_REQUESTS = 429
        const val SERVER_ERROR = 500
    }
}

/** Raised when the homeserver rejects a call — wraps Trixnity's [MatrixServerException] per the [KurierException] contract. */
internal class MatrixApiException(
    method: String,
    cause: Throwable?,
    retryable: Boolean,
) : KurierException("Matrix API $method failed: ${cause?.message ?: "unknown error"}", cause, retryable)
