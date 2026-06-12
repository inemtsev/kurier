# CLAUDE.md

## What this is

**kurier** — a unified channel adapter layer for the JVM ("JDBC for chat platforms").
One typed, coroutine/Flow-based API for bots and agents; per-platform adapters normalize
Telegram/Discord/Slack behind it. The flagship feature is **streaming-edit LLM replies**
(`reply(tokens: Flow<String>)` progressively edits the sent message, throttled per platform).

**Target:** 0.1.0 on Maven Central (see Roadmap). **North star:** kurier later becomes the
channel module of an Android on-phone agent gateway, so Android compatibility is non-negotiable.

**Maven coordinates:** group id `com.eventslooped` (author's domain, Sonatype-verifiable; wired up at M4) —
artifacts `kurier-core`, `kurier-runtime`, `kurier-adapter-*`, `kurier-testing`. Code packages stay `dev.kurier`
(library-branded packages outlive group ids; group≠package is standard — cf. Koin, OkHttp, Coil). Do not rename packages.

## Commands

```bash
./gradlew build                                  # full build: compile + tests + ktlint + detekt — run before declaring any change done
./gradlew :runtime:test                          # gateway tests only
./gradlew ktlintFormat                           # auto-fix formatting (run before check if you touched code)
./gradlew ktlintCheck detekt                     # lint only
./gradlew :samples:echo-bot:run                  # interactive end-to-end demo (no tokens needed)
printf "hi\n" | ./gradlew :samples:echo-bot:run -q   # non-interactive smoke test
```

## Module map

| Module | Role | Key constraint |
|---|---|---|
| `core` | Public API + adapter SPI | Pure Kotlin; coroutines is the only dependency |
| `runtime` | `chatGateway {}` DSL + `DefaultChatGateway` (supervision, flow merging) | Depends only on `core` |
| `adapter-telegram` | Telegram Bot API, built directly on Ktor client | M1 |
| `adapter-discord` | Wraps Kord | M2 |
| `testing` | `FakeAdapter`/`FakeChannel` for unit-testing bots | Published artifact, not test-only code |
| `samples/echo-bot` | Runnable demo | Exempt from library rules |

## Hard rules

1. **`core` stays pure.** No JVM-only dependencies, no `java.*` types in public signatures
   (`kotlin.time.Duration`, not `java.time`). It must remain mechanically promotable to KMP `commonMain`.
2. **Android-safe everywhere.** JVM 17 bytecode target; no `java.desktop`/`java.awt`; no reflection-heavy dependencies.
3. **`explicitApi()` strict** on every library module. Samples are exempt.
4. **Adapters wrap, never reimplement** (Kord, Slack SDK do protocol work; Telegram is the one exception — its Bot API
   is simple enough for a direct Ktor implementation). Adapters own reconnection, backoff, and rate limiting.
5. **SDK types never leak into `core` signatures.** Platform objects are reachable only via the escape hatch:
   `raw: Any?` in core, typed extension accessors in adapter modules (e.g. `message.telegram`).
6. **Capabilities over lowest-common-denominator.** Optional features (`react`, `indicateTyping`, buttons) degrade to
   no-ops, never throw. Anything platform-dependent is queryable via `supports(Capability)`.

## API design conventions

- `suspend` for anything that does I/O; `Flow` for streams. No blocking calls, no `GlobalScope`, no `runBlocking` in library code.
- Value classes for identifiers (`PlatformId`, `ChannelId`, `MessageId`); channel ids follow `"<platform>:<native id>"`.
- Options via data classes with default parameters (`StreamingOptions`), not builders.
- Pre-0.1.0: breaking API changes are fine and expected — improve the design now, it's the cheapest it will ever be.
  Post-0.1.0: deprecation cycle required.

## Testing conventions

- Bot/gateway logic is tested through `testing`'s `FakeAdapter` — no network, no tokens, **no sleeps**.
  Synchronization is structural: `FakeAdapter.receive()` suspends until the gateway is subscribed
  (`subscriptionCount.first { it > 0 }`). Follow this pattern for any new synchronization point.
- `runTest` from kotlinx-coroutines-test, `kotlin.test` assertions, JUnit 5 platform.
- Every adapter eventually gets (M3): golden tests for the rich-text rendering matrix + a shared SPI contract test suite.

## Style & linting

- **ktlint** (intellij_idea style) + **detekt** are enforced; both hook into `./gradlew build` via `check`.
  Config: `.editorconfig` (ktlint) and `config/detekt/detekt.yml` (overrides on top of detekt defaults).
- Trailing commas in multiline declarations and calls (enforced via `.editorconfig`); max line length 140.
  `class-signature`/`function-signature` collapsing is disabled — multiline formatting is a deliberate readability choice.
- Fix code rather than silencing rules; if a rule must be tuned, do it in `detekt.yml` with a comment explaining why.
- File names match their single top-level declaration (`ChatGateway.kt`, `IncomingMessage.kt`) — both linters enforce this.
- KDoc on public API — explain *why* and platform caveats, not what the signature already says. Comment density is light; keep it that way.
- All dependencies go through `gradle/libs.versions.toml`. Adding a dependency to `core` or `runtime` requires strong
  justification — the whole value proposition is being a thin, trustworthy layer.

## Git

- Branch: `main`. Small, focused commits; imperative subjects.
- Never commit tokens; samples read credentials from env vars only.

## Roadmap

M1 Telegram adapter → M2 Discord + streaming-edit replies → M3 Slack (Socket Mode) + rendering matrix + SPI contract tests → M4 docs + 0.1.0 on Maven Central.
Full plan and API design rationale live in the author's notes, not this repo.
