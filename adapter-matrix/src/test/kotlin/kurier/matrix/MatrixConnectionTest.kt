package kurier.matrix

import kurier.ChannelId
import kurier.ConnectionState
import kurier.PlatformId
import net.folivo.trixnity.clientserverapi.client.SyncState
import net.folivo.trixnity.core.model.RoomId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatrixConnectionTest {

    @Test
    fun `sync state maps onto connection state`() {
        assertEquals(ConnectionState.Connected, SyncState.RUNNING.toConnectionState())
        // A long-poll timeout is the normal heartbeat, not a problem — still connected.
        assertEquals(ConnectionState.Connected, SyncState.TIMEOUT.toConnectionState())
        assertEquals(ConnectionState.Connecting, SyncState.INITIAL_SYNC.toConnectionState())
        assertEquals(ConnectionState.Connecting, SyncState.STARTED.toConnectionState())
        assertEquals(ConnectionState.Closed, SyncState.STOPPED.toConnectionState())
        assertTrue(SyncState.ERROR.toConnectionState() is ConnectionState.Backoff)
    }

    @Test
    fun `a channel id round-trips to its room id, keeping the colons in the room id`() {
        val platform = PlatformId("matrix")
        val roomId = RoomId("!abc:server.com")
        val channelId = ChannelId("${platform.value}:${roomId.full}")

        assertEquals(roomId, roomIdOf(channelId, platform))
    }

    @Test
    fun `a channel id from another platform does not resolve`() {
        assertNull(roomIdOf(ChannelId("telegram:42"), PlatformId("matrix")))
    }
}
