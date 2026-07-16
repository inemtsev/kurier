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
import kurier.slack.SlackAdapter
import kurier.telegram.TelegramAdapter
import kurier.testing.FakeAdapter
import kurier.text
import kurier.twitch.TwitchAdapter
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * Echo demo. Installs one adapter per bot token present (`TG_TOKEN`, `DISCORD_TOKEN`,
 * `SLACK_BOT_TOKEN`+`SLACK_APP_TOKEN`, …, from the environment or a `.env` at the repo root) and
 * echoes across all of them through one gateway; with no token it runs an in-memory console echo.
 */
suspend fun main(): Unit = coroutineScope {
    val token = tokenSource()
    val adapters = buildList {
        token("TG_TOKEN")?.let { add(TelegramAdapter(it)) }
        token("DISCORD_TOKEN")?.let { add(DiscordAdapter(it)) }
        val matrixHome = token("MATRIX_HOMESERVER")
        val matrixToken = token("MATRIX_TOKEN")
        if (matrixHome != null && matrixToken != null) add(MatrixAdapter(matrixHome, matrixToken))
        val slackBotToken = token("SLACK_BOT_TOKEN")
        val slackAppToken = token("SLACK_APP_TOKEN")
        if (slackBotToken != null && slackAppToken != null) {
            add(SlackAdapter(botToken = slackBotToken, appToken = slackAppToken))
        }
        val twitchClientId = token("TWITCH_CLIENT_ID")
        val twitchToken = token("TWITCH_TOKEN")
        val twitchChannel = token("TWITCH_CHANNEL")
        if (twitchClientId != null && twitchToken != null && twitchChannel != null) {
            add(
                TwitchAdapter(
                    clientId = twitchClientId,
                    accessToken = twitchToken,
                    channel = twitchChannel,
                    // Optional: with the secret + refresh token, an expired access token refreshes itself.
                    clientSecret = token("TWITCH_CLIENT_SECRET"),
                    refreshToken = token("TWITCH_REFRESH_TOKEN"),
                ),
            )
        }
    }
    if (adapters.isEmpty()) consoleEcho() else liveEcho(adapters)
}

/**
 * Reads a token from the environment, falling back to a `.env` file found at or above the working
 * directory. The fallback matters for IDE launches: IntelliJ's gutter arrow generates its own exec
 * task with its own environment, bypassing whatever the Gradle `run` task injects. Blank values
 * count as absent.
 */
private fun tokenSource(): (String) -> String? {
    val dotEnv = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .take(4)
        .map { it.resolve(".env") }
        .firstOrNull { it.isFile }
        ?.readLines()
        ?.filter { it.isNotBlank() && !it.startsWith("#") && "=" in it }
        ?.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        .orEmpty()
    return { key -> (System.getenv(key) ?: dotEnv[key])?.takeIf { it.isNotBlank() } }
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
