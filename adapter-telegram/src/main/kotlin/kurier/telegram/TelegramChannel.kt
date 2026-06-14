package kurier.telegram

import kotlinx.coroutines.flow.Flow
import kurier.Capability
import kurier.Channel
import kurier.ChannelId
import kurier.ChannelKind
import kurier.Content
import kurier.PlatformId
import kurier.SentMessage
import kurier.StreamingOptions

/**
 * A Telegram chat exposed as a kurier [Channel]. For slices 1–3 this carries only
 * identity so incoming messages can be normalized and routed; the outbound paths
 * land in later slices. [supports] already reports Telegram's real capability set.
 */
internal class TelegramChannel(
    override val id: ChannelId,
    override val platform: PlatformId,
    override val kind: ChannelKind,
    override val name: String?,
) : Channel {

    /**
     * Reports what the Telegram platform can do — not what this adapter has wired yet.
     * Callers branch on this; unimplemented features degrade to no-ops rather than throw.
     * Contract: every `true` here must be backed by a real implementation by 0.1.0.
     */
    override fun supports(capability: Capability): Boolean = when (capability) {
        Capability.EDITING,
        Capability.REACTIONS,
        Capability.FILES,
        Capability.TYPING,
        Capability.BUTTONS,
        -> true

        // Provisional: Telegram does have forum topics (THREADS) and voice messages (VOICE);
        // revisit in M3 once each Capability's cross-platform meaning is defined.
        Capability.THREADS,
        Capability.VOICE,
        -> false
    }

    override suspend fun send(content: Content): SentMessage =
        TODO("M1 slice 5: POST sendMessage with MarkdownV2-rendered content")

    override suspend fun sendStreaming(tokens: Flow<String>, options: StreamingOptions): SentMessage =
        TODO("M2: throttled progressive editMessageText")
}
