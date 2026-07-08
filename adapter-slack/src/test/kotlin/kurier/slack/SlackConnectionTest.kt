package kurier.slack

import com.slack.api.socket_mode.request.EventsApiEnvelope
import kurier.ConnectionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlackConnectionTest {

    @Test
    fun `hello maps to Connected`() {
        assertEquals(ConnectionState.Connected, SlackSignal.Hello.toConnectionState())
    }

    @Test
    fun `a socket close maps to Connecting because the SDK reconnects itself`() {
        assertEquals(ConnectionState.Connecting, SlackSignal.SocketClosed(code = 1006, reason = "abnormal").toConnectionState())
    }

    @Test
    fun `a socket error maps to Backoff carrying the cause`() {
        val cause = IllegalStateException("socket burst")

        val state = assertIs<ConnectionState.Backoff>(SlackSignal.SocketError(cause).toConnectionState())
        assertEquals(cause, state.cause)
    }

    @Test
    fun `an envelope carries payload rather than state`() {
        assertNull(SlackSignal.Envelope(EventsApiEnvelope.builder().envelopeId("e1").build()).toConnectionState())
    }

    @Test
    fun `a redelivered envelope id is remembered exactly once`() {
        val ids = RecentIds(capacity = 8)

        assertTrue(ids.remember("e1"))
        assertFalse(ids.remember("e1"))
        assertTrue(ids.remember("e2"))
    }

    @Test
    fun `eviction forgets the oldest id`() {
        val ids = RecentIds(capacity = 2)
        ids.remember("e1")
        ids.remember("e2")
        ids.remember("e3") // evicts e1

        assertTrue(ids.remember("e1"))
        assertFalse(ids.remember("e3"))
    }

    @Test
    fun `a null id is never deduplicated`() {
        val ids = RecentIds(capacity = 2)

        assertTrue(ids.remember(null))
        assertTrue(ids.remember(null))
    }
}
