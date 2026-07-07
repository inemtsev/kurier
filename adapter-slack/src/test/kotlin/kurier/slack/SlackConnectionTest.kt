package kurier.slack

import com.slack.api.socket_mode.request.EventsApiEnvelope
import kurier.ConnectionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

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
}
