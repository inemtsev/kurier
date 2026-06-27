package kurier.twitch

import kurier.ChannelId
import kurier.Content
import kurier.MessageId
import kurier.SentMessage

/**
 * A chat message kurier posted to Twitch. Twitch chat is immutable from the sender's side — there is
 * no edit endpoint — so [edit] is a no-op, consistent with [kurier.Capability.EDITING] being
 * unsupported (and why streaming replies buffer rather than edit). [delete] is likewise a no-op for
 * now: removing a message needs the moderation endpoint plus `moderator:manage:chat_messages` scope,
 * which TW-2 doesn't require; it can be wired once that scope is part of the adapter's contract.
 */
internal class TwitchSentMessage(
    override val id: MessageId,
    override val channelId: ChannelId,
) : SentMessage {

    override suspend fun edit(content: Content) {
        // no-op: Twitch chat messages cannot be edited
    }

    override suspend fun delete() {
        // no-op: deletion requires moderator scope; deferred
    }
}
