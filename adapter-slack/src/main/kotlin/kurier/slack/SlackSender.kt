package kurier.slack

import com.slack.api.methods.MethodsClient
import com.slack.api.methods.SlackApiException
import com.slack.api.methods.SlackApiTextResponse
import com.slack.api.methods.request.chat.ChatDeleteRequest
import com.slack.api.methods.request.chat.ChatPostMessageRequest
import com.slack.api.methods.request.chat.ChatUpdateRequest
import com.slack.api.methods.request.reactions.ReactionsAddRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kurier.KurierException
import kurier.MessageId
import java.io.IOException

/**
 * The outbound operations a [SlackChannel] needs, abstracted away from the Slack SDK. The Web API
 * client is blocking and HTTP-real, so this seam lets the channel's send/edit/stream logic be
 * unit-tested with a fake — the real implementation ([MethodsSlackSender]) is the only SDK-touching part.
 */
internal interface SlackSender {
    suspend fun send(text: String): MessageId

    suspend fun edit(messageId: MessageId, text: String)

    suspend fun delete(messageId: MessageId)

    /** [name] is a Slack emoji shortcode (`"thumbsup"`), not a unicode emoji. */
    suspend fun react(messageId: MessageId, name: String)
}

/** The real [SlackSender], backed by the blocking [MethodsClient] against one channel. */
internal class MethodsSlackSender(
    private val methods: MethodsClient,
    private val channel: String,
) : SlackSender {

    override suspend fun send(text: String): MessageId {
        val response = call("chat.postMessage") {
            methods.chatPostMessage(ChatPostMessageRequest.builder().channel(channel).text(text).build())
        }
        return MessageId(response.ts)
    }

    override suspend fun edit(messageId: MessageId, text: String) {
        call("chat.update") {
            methods.chatUpdate(ChatUpdateRequest.builder().channel(channel).ts(messageId.value).text(text).build())
        }
    }

    override suspend fun delete(messageId: MessageId) {
        call("chat.delete") {
            methods.chatDelete(ChatDeleteRequest.builder().channel(channel).ts(messageId.value).build())
        }
    }

    override suspend fun react(messageId: MessageId, name: String) {
        call("reactions.add") {
            methods.reactionsAdd(ReactionsAddRequest.builder().channel(channel).timestamp(messageId.value).name(name).build())
        }
    }

    // runInterruptible keeps the blocking OkHttp call cancellation-responsive: cancelling a streaming
    // reply interrupts the in-flight edit instead of letting it run to completion.
    private suspend fun <T : SlackApiTextResponse> call(method: String, request: () -> T): T =
        try {
            runInterruptible(Dispatchers.IO) { request() }.okOrThrow(method)
        } catch (failure: SlackApiException) {
            throw KurierException("Slack API $method failed: HTTP ${failure.response.code}", failure, failure.isRetryable())
        } catch (failure: IOException) {
            throw KurierException("Slack API $method transport failure", failure, retryable = true)
        }
}

private fun <T : SlackApiTextResponse> T.okOrThrow(method: String): T =
    if (isOk) this else throw SlackApiCallException(method, error)

/**
 * A Slack Web API call answered `ok=false` — an HTTP-200 logical error such as `ratelimited` or
 * `channel_not_found`. Transport and HTTP-status failures are wrapped into [KurierException] at the
 * call site. (Not named after the SDK's `SlackApiException`, which this module imports alongside it.)
 */
internal class SlackApiCallException(
    method: String,
    error: String?,
) : KurierException("Slack API $method failed: ${error ?: "unknown error"}", retryable = error == "ratelimited")
