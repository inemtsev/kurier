package kurier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IdsTest {

    private val slack = PlatformId("slack")
    private val matrix = PlatformId("matrix")

    @Test
    fun `of builds the canonical form and nativeId parses it back`() {
        val id = ChannelId.of(slack, "C42")

        assertEquals(ChannelId("slack:C42"), id)
        assertEquals("C42", id.nativeId(slack))
    }

    @Test
    fun `nativeId splits at the first separator so native ids may contain colons`() {
        assertEquals("!room:server.org", ChannelId.of(matrix, "!room:server.org").nativeId(matrix))
    }

    @Test
    fun `nativeId rejects a foreign platform prefix`() {
        assertNull(ChannelId("discord:42").nativeId(slack))
    }

    @Test
    fun `nativeId rejects ids without a separator or with a blank native part`() {
        assertNull(ChannelId("slack").nativeId(slack))
        assertNull(ChannelId("slack:").nativeId(slack))
        assertNull(ChannelId("slack: ").nativeId(slack))
    }
}
