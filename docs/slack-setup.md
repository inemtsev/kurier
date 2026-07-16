# Slack adapter setup

Slack needs more setup than the other platforms: two tokens instead of one, and an app whose
Socket Mode and event subscriptions are configured by hand. A free **workspace** is enough — no
organization (Enterprise Grid) required — and a throwaway test workspace keeps the bot out of your
real one.

| Token | Prefix | Purpose | Env var |
|---|---|---|---|
| Bot token | `xoxb-…` | Web API calls (`chat.postMessage`, `chat.update`, `reactions.add`, …) | `SLACK_BOT_TOKEN` |
| App-level token | `xapp-…` | Opens the Socket Mode connection (needs `connections:write`) | `SLACK_APP_TOKEN` |

## Steps

1. **Create a workspace** (if you don't have one to test in): [slack.com/create](https://slack.com/create).
2. **Create the app**: [api.slack.com/apps](https://api.slack.com/apps) → *Create New App* → *From
   scratch* → pick the workspace.
3. **Enable Socket Mode**: *Settings → Socket Mode* → toggle on. The prompt generates an
   **app-level token** with `connections:write` — this is `SLACK_APP_TOKEN`.
4. **Bot token scopes**: *OAuth & Permissions → Bot Token Scopes* — add `chat:write`,
   `channels:history`, `im:history`, `reactions:write` (plus `groups:history` / `mpim:history` for
   private channels and group DMs).
5. **Event subscriptions**: *Event Subscriptions* → enable → *Subscribe to bot events*:
   `message.channels`, `message.im`. No request URL is needed — Socket Mode delivers events over
   the websocket. (`app_mention` is optional; the adapter ignores those envelopes because the
   `message.*` events already carry mentions — subscribing to both would double-deliver.)
6. **Install the app** to the workspace (*Install App*). This issues the **bot token** —
   `SLACK_BOT_TOKEN`. Scopes added later require a reinstall to take effect.
7. **Invite the bot** to a channel: `/invite @yourbot` (or just DM it).
8. **Run**: put both tokens in `.env` at the repo root and start the echo bot.

```bash
# .env
SLACK_BOT_TOKEN=xoxb-…
SLACK_APP_TOKEN=xapp-…
```

```bash
./gradlew :samples:echo-bot:run
```

## What to expect

- The connection log should show `slack=Connecting` → `slack=Connected` once Slack's `hello` frame
  lands after the Socket Mode handshake.
- The echo bot replies only to messages **directed at it** — DM the bot, or `@mention` it in the
  channel it was invited to.
- The echo reply exercises inbound + outbound but not editing; to see the streaming-edit path,
  reply with a token `Flow` (`message.reply(tokens)`) from your own bot code.

## Troubleshooting

- `slack=Failed(… auth.test failed: invalid_auth)` — the bot token is wrong or revoked.
- `Failed` right after start with an `apps.connections.open` error — the app token is wrong, or
  Socket Mode isn't enabled.
- Messages don't arrive — the bot isn't in the channel, or the `message.channels` / `message.im`
  event subscription is missing.
- `missing_scope` on send/react — add the scope (step 4) and **reinstall the app** (step 6).
- `react()` takes Slack emoji **shortcodes** (`"thumbsup"`), not unicode (`"👍"`).
