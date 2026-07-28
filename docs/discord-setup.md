# Discord adapter setup

Discord needs a single token, but the Developer Portal shows several look-alike credentials and hides one required
toggle: the **Message Content** privileged intent. Without that toggle the adapter connects and is immediately closed
with `4014 Disallowed intent(s)`, which is by far the most common Discord setup failure.

| Credential | Where in the portal | Purpose | Needed here? |
|---|---|---|---|
| **Bot token** | *Bot* tab → Token | Authenticates the gateway + REST calls | ✅ `DISCORD_TOKEN` |
| Public Key | *General Information* | Verifies interaction webhook signatures | ❌ |
| Application ID | *General Information* | Identifies the app in OAuth/invite URLs | Only inside the invite URL |
| Client Secret | *OAuth2* | OAuth2 code exchange | ❌ |

## Steps

1. **Create the app**: [discord.com/developers/applications](https://discord.com/developers/applications)
   → *New Application*.
2. **Get the bot token**: *Bot* tab → *Reset Token* → copy. Discord shows a token **once**, at
   creation or reset. If you didn't save it, resetting is the normal way to get a new one
   (resetting invalidates all previous tokens). This is `DISCORD_TOKEN`.
3. **Enable the Message Content intent**: still on the *Bot* tab, under **Privileged Gateway
   Intents**, switch on **Message Content Intent** and **Save**. The adapter requests this intent
   at gateway login (it is how message text arrives), so the toggle is required. The other intents
   it requests (guild/DM messages, guild/DM reactions) are standard and always allowed; *Presence*
   and *Server Members* are **not** needed.
4. **Invite the bot** to a server: *OAuth2 → URL Generator* → scope **bot** → a *Bot Permissions*
   grid appears; check *View Channels*, *Send Messages*, *Read Message History*
   (+ *Add Reactions* for `react()`) → open the **Generated URL** from the bottom of the page in a
   browser, pick the server, *Authorize*. Two notes:
   - The server dropdown only lists servers where **you** hold the *Manage Server* permission.
     Create a throwaway server first if you don't have one.
   - Newer portal layouts route this through an **Installation** tab instead; make sure
     *Guild Install* is enabled there. The generated-URL flow is the same idea.
5. **Run**: put the token in `.env` at the repo root and start the echo bot.

```bash
# .env
DISCORD_TOKEN=…
```

```bash
./gradlew :samples:echo-bot:run
```

## What to expect

- The connection log should show `discord=Connecting` → `discord=Connected` once the gateway
  handshake completes.
- The echo bot replies only to messages **directed at it**:
  - `@mention` it in a channel, picking the bot from the autocomplete popup as you type `@`.
    Literally typed `@botname` text is not a mention and is ignored.
  - Or **DM it**: right-click the bot in the server's member list → *Message*. DMs count as
    directed automatically, no mention needed.
- Replies **edit in place** when driven by a token `Flow` (`message.reply(tokens)`); Discord
  supports the streaming-edit path natively.
- Reactions arrive as `ReactionAdded` events with canonical unicode emoji (`"👍"`), and `react()`
  takes unicode too.

## Troubleshooting

- `Gateway closed: 4014 Disallowed intent(s)`: the Message Content toggle is off (step 3), or it
  was enabled on a **different application** than the one the token belongs to. Enable, *Save*,
  restart the bot; the toggle takes effect on the next connection.
- `401: Unauthorized` at connect: the token is wrong, or was invalidated by a later *Reset Token*.
- The bot is in the server but never replies: the message wasn't directed at it (mention via
  autocomplete, don't type the text), or a channel-level permission override hides the channel or
  blocks *Send Messages* for the bot's role.
- Works in one server, silent in another: per-server role/channel permission overrides. Re-check
  *View Channels* / *Send Messages* in that server.
- Planning to grow: past **100 servers** Discord requires app verification to keep the Message
  Content intent; below that the toggle is self-service.
