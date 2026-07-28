# Changelog

All notable changes to kurier are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/) (0.x minors may evolve the API additively;
breaking changes go through a deprecation cycle).

## [Unreleased]

## [0.1.0] - 2026-07-27

First public release.

### Added

- **Core API** (`kurier-core`): `ChatGateway`, `IncomingMessage`, `Channel`, `SentMessage`,
  `Content`/`RichText` (+ `richText {}` DSL), value-class ids, `Capability` queries, and the
  `ChannelAdapter`/`AdapterConnection` SPI with a documented contract (hot non-replaying flows,
  never-throw rule, self-message filtering, `<platform>:<native id>` channel ids).
- **Gateway runtime** (`kurier-runtime`): the `chatGateway {}` DSL and per-adapter supervision.
  A failing platform never takes down the rest, and a broken adapter can't crash the host process.
- **Five adapters**: Telegram (Bot API long-polling), Discord (Kord), Matrix (Trixnity `/sync`),
  Twitch (EventSub WebSocket + Helix), and Slack (Socket Mode); no webhook server required
  anywhere.
- **Streaming-edit replies**: `reply(tokenFlow)` progressively edits one message as tokens arrive,
  throttled per platform; buffers (with a live typing indicator) where editing isn't available;
  finalizes the partial message best-effort on mid-stream failure.
- **Native reply-linking**: `reply()` threads on Slack, message references on Discord, replies on
  Telegram; degrades to a plain send elsewhere.
- **Portable error handling**: platform failures surface as `KurierException` with a `retryable`
  signal and the platform exception as `cause`; reactions and typing are best-effort no-ops.
- **Reactions with a unicode-canonical contract**: `react("👍")` works everywhere it can;
  Slack shortcodes are translated both ways.
- **Testing artifacts**: `kurier-testing` (`FakeAdapter`/`FakeChannel`, no network, no sleeps) and
  `kurier-testing-contract` (the shared SPI conformance suite + rendering-matrix samples).
- **API stability enforcement**: binary-compatibility baselines checked in CI for every published
  module.

[Unreleased]: https://github.com/inemtsev/kurier/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/inemtsev/kurier/releases/tag/v0.1.0
