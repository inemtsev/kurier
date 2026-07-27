package kurier.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kurier.AdapterConnection
import kurier.Channel
import kurier.ChannelAdapter
import kurier.ChannelEvent
import kurier.ChannelId
import kurier.ConnectionState
import kurier.IncomingMessage
import kurier.MessageId
import kurier.PlatformId
import kurier.reply
import kurier.testing.FakeAdapter
import kurier.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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

    @Test
    fun `stop marks every platform Closed and the gateway can restart`() = runTest {
        val fake = FakeAdapter()
        val gateway = chatGateway { install(fake) }
        gateway.start()
        gateway.connections.first { it.isNotEmpty() }

        gateway.stop()
        assertEquals(ConnectionState.Closed, gateway.connections.value[PlatformId("fake")])

        gateway.start()
        val next = async(start = CoroutineStart.UNDISPATCHED) { gateway.messages.first() }
        fake.receive("again")
        assertEquals("again", next.await().text)

        gateway.stop()
    }

    @Test
    fun `emissions immediately after start are not lost to the subscribe race`() = runTest {
        // A rendezvous flow, like every in-repo adapter: emissions with no subscriber are dropped,
        // so this only passes if the gateway's collectors subscribe before start() returns.
        val eventFlow = MutableSharedFlow<ChannelEvent>()
        val eager = object : ChannelAdapter {
            override val platform = PlatformId("eager")

            override fun connect(scope: CoroutineScope): AdapterConnection = object : AdapterConnection {
                override val messages: Flow<IncomingMessage> = emptyFlow()
                override val events: Flow<ChannelEvent> = eventFlow
                override val state: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Connected)

                override fun channel(id: ChannelId): Channel? = null

                override suspend fun close() = Unit
            }
        }
        val gateway = chatGateway { install(eager) }
        val received = async(start = CoroutineStart.UNDISPATCHED) { gateway.events.first() }
        gateway.start()

        val deleted = ChannelEvent.MessageDeleted(ChannelId("eager:1"), MessageId("m1"))
        eventFlow.emit(deleted)

        assertEquals(deleted, received.await())
        gateway.stop()
    }

    @Test
    fun `a throwing adapter flow fails only its own platform`() = runTest {
        val bad = object : ChannelAdapter {
            override val platform = PlatformId("bad")

            override fun connect(scope: CoroutineScope): AdapterConnection = object : AdapterConnection {
                // Violates the SPI's never-throw rule on purpose; the gateway must contain it.
                override val messages: Flow<IncomingMessage> = flow { error("broken adapter") }
                override val events: Flow<ChannelEvent> = emptyFlow()
                override val state: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Connected)

                override fun channel(id: ChannelId): Channel? = null

                override suspend fun close() = Unit
            }
        }
        val good = FakeAdapter()
        val gateway = chatGateway {
            install(bad)
            install(good)
        }
        gateway.start()

        val received = async(start = CoroutineStart.UNDISPATCHED) { gateway.messages.first() }
        good.receive("still alive")
        assertEquals("still alive", received.await().text)
        val badState = gateway.connections.first { it[PlatformId("bad")] is ConnectionState.Failed }
        assertIs<ConnectionState.Failed>(badState[PlatformId("bad")])

        gateway.stop()
    }
}
