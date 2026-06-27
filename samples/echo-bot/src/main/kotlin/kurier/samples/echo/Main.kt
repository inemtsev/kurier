package kurier.samples.echo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kurier.ChannelAdapter
import kurier.chatGateway
import kurier.discord.DiscordAdapter
import kurier.matrix.MatrixAdapter
import kurier.reply
import kurier.telegram.TelegramAdapter
import kurier.testing.FakeAdapter
import kurier.text
import kurier.twitch.TwitchAdapter
import kotlin.time.Duration.Companion.seconds

/**
 * Echo demo. Installs one adapter per bot token present (`TG_TOKEN`, `DISCORD_TOKEN`, e.g. in `.env`)
 * and echoes across all of them through one gateway; with no token it runs an in-memory console echo.
 */
suspend fun main(): Unit = coroutineScope {
    val adapters = buildList {
        System.getenv("TG_TOKEN")?.takeIf { it.isNotBlank() }?.let { add(TelegramAdapter(it)) }
        System.getenv("DISCORD_TOKEN")?.takeIf { it.isNotBlank() }?.let { add(DiscordAdapter(it)) }
        val matrixHome = System.getenv("MATRIX_HOMESERVER")
        val matrixToken = System.getenv("MATRIX_TOKEN")
        if (!matrixHome.isNullOrBlank() && !matrixToken.isNullOrBlank()) add(MatrixAdapter(matrixHome, matrixToken))
        val twitchClientId = System.getenv("TWITCH_CLIENT_ID")
        val twitchToken = System.getenv("TWITCH_TOKEN")
        val twitchChannel = System.getenv("TWITCH_CHANNEL")
        if (!twitchClientId.isNullOrBlank() && !twitchToken.isNullOrBlank() && !twitchChannel.isNullOrBlank()) {
            add(TwitchAdapter(clientId = twitchClientId, accessToken = twitchToken, channel = twitchChannel))
        }
    }
    if (adapters.isEmpty()) consoleEcho() else liveEcho(adapters)
}

private suspend fun CoroutineScope.liveEcho(adapters: List<ChannelAdapter>) {
    val gateway = chatGateway { adapters.forEach { install(it) } }
    gateway.start()
    launch { gateway.connections.collect { println("[kurier] $it") } }
    launch { gateway.events.collect { println("[kurier] event $it") } }

    println("kurier echo-bot — message your bot; Ctrl+C to stop")
    gateway.messages.collect { message ->
        val who = message.author.displayName ?: message.author.id
        println("[kurier] in  <${message.channel.platform}:$who> directed=${message.isDirectedAtBot}: ${message.text}")
        if (message.isDirectedAtBot) {
            message.reply("echo: ${message.text}")
            println("[kurier] out echo: ${message.text}")
        }
    }
}

private suspend fun CoroutineScope.consoleEcho() {
    val console = FakeAdapter(id = "console", onSend = { _, content -> println("bot> ${content.text}") })
    val gateway = chatGateway { install(console) }
    gateway.start()

    val bot = launch {
        gateway.messages.collect { message -> message.reply("echo: ${message.text}") }
    }

    println("kurier echo-bot — type a message, Ctrl+D to exit (set TG_TOKEN or DISCORD_TOKEN for live)")
    var received = 0
    // The blocking readlnOrNull() must run on Dispatchers.IO: `suspend main` has no
    // dispatcher, so without one this loop would resume nested on the gateway's worker
    // thread after each receive() and block it until the next input line.
    withContext(Dispatchers.IO) {
        generateSequence(::readlnOrNull)
            .filter { it.isNotBlank() }
            .forEach { line ->
                console.receive(line)
                received++
            }
    }

    // Let in-flight echoes drain before shutting down (matters for piped input).
    withTimeoutOrNull(2.seconds) {
        while (console.sent.size < received) delay(10)
    }

    bot.cancel()
    gateway.stop()
}
