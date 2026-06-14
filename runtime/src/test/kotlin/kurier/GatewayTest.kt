package kurier

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kurier.testing.FakeAdapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GatewayTest {

    @Test
    fun `delivers incoming messages and routes replies back to the adapter`() = runTest {
        val fake = FakeAdapter()
        val gateway = chatGateway { install(fake) }
        gateway.start()

        val firstMessage = async(start = CoroutineStart.UNDISPATCHED) { gateway.messages.first() }
        fake.receive("hello")

        val message = firstMessage.await()
        assertEquals("hello", message.text)
        assertTrue(message.isDirectedAtBot)
        assertEquals(PlatformId("fake"), message.channel.platform)

        message.reply("world")
        assertEquals("world", fake.sent.single().text)

        gateway.stop()
    }

    @Test
    fun `supports multiple adapters of the same platform under distinct ids`() = runTest {
        val main = FakeAdapter(id = "telegram-main")
        val support = FakeAdapter(id = "telegram-support")
        val gateway = chatGateway {
            install(main)
            install(support)
        }
        gateway.start()

        val fromSupport = async(start = CoroutineStart.UNDISPATCHED) { gateway.messages.first() }
        support.receive("help!")

        assertEquals(PlatformId("telegram-support"), fromSupport.await().channel.platform)
        val state = gateway.connections.first { it.size == 2 }
        assertEquals(ConnectionState.Connected, state[PlatformId("telegram-main")])
        assertEquals(ConnectionState.Connected, state[PlatformId("telegram-support")])

        gateway.stop()
    }

    @Test
    fun `rejects adapters with duplicate platform ids`() {
        assertFailsWith<IllegalArgumentException> {
            chatGateway {
                install(FakeAdapter(id = "telegram"))
                install(FakeAdapter(id = "telegram"))
            }
        }
    }

    @Test
    fun `reports connection state per platform`() = runTest {
        val fake = FakeAdapter()
        val gateway = chatGateway { install(fake) }
        gateway.start()

        val state = gateway.connections.first { it.isNotEmpty() }
        assertEquals(ConnectionState.Connected, state[PlatformId("fake")])

        gateway.stop()
    }
}
