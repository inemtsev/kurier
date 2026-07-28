# Contributing to kurier

Thanks for considering it. This is a small, deliberately thin library; most contributions are
adapter improvements, new adapters, or documentation. Open an issue first for anything that
touches the public API.

## The one command that matters

```bash
./gradlew build   # compile + all tests + ktlint + detekt + binary-compatibility check
```

If that's green, your change passes the same gate CI runs on every PR. Useful variants:

```bash
./gradlew ktlintFormat                           # auto-fix formatting before you commit
printf "hi\n" | ./gradlew :samples:echo-bot:run -q   # offline end-to-end smoke test
```

## Ground rules

- **`core` stays pure.** Coroutines is its only dependency; no `java.*` types in public signatures
  (`kotlin.time.Duration`, never `java.time`). It must remain promotable to KMP `commonMain`.
- **Android-safe everywhere.** JVM 17 bytecode; no `java.desktop`/`java.awt`; no reflection-heavy
  dependencies.
- **Adapters wrap, never reimplement.** Platform SDKs do the protocol work (Telegram and Twitch
  are the sanctioned direct-Ktor exceptions). Adapters own reconnection, backoff, and rate limits.
- **SDK types never leak into `core` signatures.** Platform objects travel via `raw` and typed
  accessors only.
- **Optional features degrade to no-ops, never throw.** Anything platform-dependent is queryable
  via `supports(Capability)`.
- **No tokens, ever.** Not in code, not in tests, not in fixtures. Samples read credentials from
  the environment or a gitignored `.env`.

## API stability

The public ABI of every published module is locked by the
[binary-compatibility validator](https://github.com/Kotlin/binary-compatibility-validator):
`build` fails on any surface change. If your change intentionally alters the public API,
run `./gradlew apiDump` and include the `api/<module>.api` diff in the PR; it's reviewed as part
of the change. Breaking changes require a deprecation cycle; additive changes are welcome.

## Tests

- Bot/gateway logic is tested through `kurier-testing`'s `FakeAdapter`: no network, no tokens,
  and **no sleeps**. Synchronization is structural (e.g. `subscriptionCount.first { it > 0 }`).
- Every adapter passes the shared conformance suite (`ChannelContract` in
  `kurier-testing-contract`) plus golden tests for the rich-text rendering matrix. A new adapter
  isn't done until both run against it.

## Adding a platform adapter

Read the README's "Writing an adapter (SPI)" section first; it states the contract (hot
non-replaying flows, the never-throw rule, self-message filtering, `<platform>:<native id>`
channel ids, the constructor convention). The five bundled adapters are the reference
implementations: `adapter-telegram` is the smallest end-to-end example, and each adapter module's
tests show the fake-seam pattern for testing without a network.

## Style

ktlint (IntelliJ IDEA style) and detekt are enforced; the build fails on violations. Fix code
rather than suppressing rules. If a rule must be tuned, do it in `config/detekt/detekt.yml` with
a comment explaining why. Trailing commas in multiline declarations, 140-char lines, file names
match their single top-level declaration. KDoc on public API explains *why* and platform caveats,
not what the signature already says; comment density is deliberately light.

## Commits and PRs

Small, focused commits with short imperative subjects. Update `CHANGELOG.md` under `[Unreleased]`
for anything user-visible. CI must be green.
