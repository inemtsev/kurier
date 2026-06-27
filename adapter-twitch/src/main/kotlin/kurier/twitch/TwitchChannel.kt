package kurier.twitch

import kotlinx.coroutines.flow.Flow
import kurier.Capability
import kurier.Channel
import kurier.ChannelId
import kurier.ChannelKind
import kurier.Content
import kurier.PlatformId
import kurier.SentMessage
import kurier.StreamingOptions
import kurier.sendStreamingByEditing
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A Twitch channel's chat exposed as a kurier [Channel]. Twitch chat is the leanest platform: plain
 * text only, with no editing, reactions, typing, threads, buttons, files, or voice — so [supports] is
 * uniformly false and `react`/`indicateTyping` stay inherited no-ops. [send] posts via Helix; because
 * [Capability.EDITING] is unsupported, [sendStreaming] auto-degrades to a single buffered send.
 */
internal class TwitchChannel(
    private val outbound: TwitchOutbound,
    override val id: ChannelId,
    override val platform: PlatformId,
    override val kind: ChannelKind,
    override val name: String?,
) : Channel {

    override fun supports(capability: Capability): Boolean = false

    override suspend fun send(content: Content): SentMessage =
        TwitchSentMessage(id = outbound.send(content.text), channelId = id)

    override suspend fun sendStreaming(tokens: Flow<String>, options: StreamingOptions): SentMessage =
        // Twitch has no message editing, so the shared engine auto-degrades to BUFFERED: drain, then one send.
        sendStreamingByEditing(tokens, options, MIN_EDIT_INTERVAL)

    private companion object {
        // Unused while EDITING is unsupported (streaming always buffers); kept so the contract reads sanely.
        val MIN_EDIT_INTERVAL: Duration = 1.seconds
    }
}
