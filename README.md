# kurier

> One API for chat platforms. Kotlin-native, coroutine-first.

**kurier** (German/Polish for *courier*) is a unified channel adapter layer for Kotlin — the JDBC for chat platforms. 
Write your bot or agent once against one typed, `Flow`-based API; per-platform adapters handle the rest: message normalization, rich-text dialects, media, threading, reconnection, and rate limits.

```kotlin
val gateway = chatGateway {
    install(TelegramAdapter(token = System.getenv("TG_TOKEN")))
    install(DiscordAdapter(token = System.getenv("DISCORD_TOKEN")))
}

gateway.start()
gateway.messages.collect { msg ->
    if (msg.isDirectedAtBot) {
        msg.reply(agent.run(msg.text))   // or reply(tokenFlow) for streaming LLM output
    }
}
```

## Status

🚧 **Pre-alpha.** API design phase — nothing published yet. The in-memory `FakeAdapter` and gateway runtime work end-to-end (try `./gradlew :samples:echo-bot:run`); platform adapters are in progress.

## Why

- **Streaming-first** — `reply(tokens: Flow<String>)` progressively edits the sent message as LLM tokens arrive, with per-platform edit throttling. The feature every AI agent hand-rolls today, badly.
- **Capabilities over lowest-common-denominator** — `channel.supports(BUTTONS)` checks and graceful no-ops instead of a crippled common API.
- **Typed escape hatches** — agnostic by default, never trapped: adapter modules expose the raw platform objects when you need them.
- **Testable bots** — the `testing` artifact ships a `FakeAdapter`, so your bot logic is unit-testable without tokens or network.
- **Adapters wrap, never reimplement** — Kord, Slack SDK, and friends do the protocol work; kurier does normalization.

## Modules

| Module | Purpose |
|---|---|
| `core` | Pure-Kotlin abstractions: `ChatGateway`, `Channel`, `IncomingMessage`, `RichText`, adapter SPI |
| `runtime` | Gateway implementation: adapter supervision, flow merging |
| `adapter-telegram` | Telegram Bot API (Ktor client, long polling) |
| `adapter-discord` | Discord via [Kord](https://github.com/kordlib/kord) |
| `testing` | `FakeAdapter` for unit-testing bots |
| `samples/echo-bot` | Runnable end-to-end demo, no tokens required |

## Roadmap

- **M1** — Telegram adapter, echo bot on a real platform
- **M2** — Discord adapter, streaming-edit replies
- **M3** — Slack adapter (Socket Mode), rich-text rendering matrix, golden tests
- **M4** — 0.1.0 on Maven Central

## License

[Apache 2.0](LICENSE)
