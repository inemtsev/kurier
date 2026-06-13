package dev.kurier.samples.echo

import dev.kurier.chatGateway
import dev.kurier.reply
import dev.kurier.testing.FakeAdapter
import dev.kurier.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Minimal end-to-end demo using the in-memory FakeAdapter — no tokens needed.
 * Type a line, the bot echoes it back through the gateway.
 *
 * Once the Telegram adapter lands (M1), swapping `FakeAdapter` for
 * `TelegramAdapter(token)` is the only change required.
 */
suspend fun main(): Unit = coroutineScope {
    val console = FakeAdapter(id = "console", onSend = { _, content ->
        println("bot> ${content.text}")
    })

    val gateway = chatGateway { install(console) }
    gateway.start()

    val bot = launch {
        gateway.messages.collect { message ->
            message.reply("echo: ${message.text}")
        }
    }

    println("kurier echo-bot — type a message, Ctrl+D to exit")
    var received = 0
    // The blocking readlnOrNull() must run on Dispatchers.IO: `suspend main` has no
    // dispatcher, so without one this loop would resume *nested on the gateway's
    // worker thread* after each receive() and block it until the next input line.
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
