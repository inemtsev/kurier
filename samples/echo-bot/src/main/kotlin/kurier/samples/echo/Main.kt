package kurier.samples.echo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kurier.chatGateway
import kurier.reply
import kurier.telegram.TelegramAdapter
import kurier.testing.FakeAdapter
import kurier.text
import kotlin.time.Duration.Companion.seconds

/**
 * Echo demo. With no `TG_TOKEN` it runs an in-memory console echo (no tokens needed);
 * set `TG_TOKEN` (e.g. in `.env`) to echo against a real Telegram bot instead.
 */
suspend fun main(): Unit = coroutineScope {
    val token = System.getenv("TG_TOKEN")
    if (token.isNullOrBlank()) consoleEcho() else telegramEcho(token)
}

private suspend fun CoroutineScope.telegramEcho(token: String) {
    val gateway = chatGateway { install(TelegramAdapter(token)) }
    gateway.start()
    launch { gateway.connections.collect { println("[kurier] $it") } }

    println("kurier telegram echo-bot — message your bot; Ctrl+C to stop")
    gateway.messages.collect { message ->
        val who = message.author.displayName ?: message.author.id
        println("[kurier] in  <$who> directed=${message.isDirectedAtBot}: ${message.text}")
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

    println("kurier echo-bot — type a message, Ctrl+D to exit (set TG_TOKEN for live Telegram)")
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
