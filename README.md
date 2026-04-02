# AS Chat

AS Chat is a client-only Fabric mod for Minecraft `1.21.11` that adds a private relay-backed chat channel and a small set of Wynncraft helper commands.

It was built for situations where you do **not** control the Minecraft server, but still want a shared private chat for people using the mod.

## What It Does

- Adds `/as <message>` for a private relay chat channel
- Adds `/ast` lookups for Wynncraft player, guild, and playtime info
- Lets players hide shouts, ignore AS chat users, and enable local session logging
- Supports a client-side word filter GUI through `/asconfig filter`
- Uses a Wynntils-style `AS7` glyph prefix for chat presentation

## How It Works

This mod does **not** use custom Fabric packets on the Minecraft server.

Instead, the flow is:

```text
Player client mod -> HTTP relay -> other player client mods
```

When a player sends `/as hello`:

1. The client command is intercepted locally.
2. The mod sends the message to a small HTTP relay.
3. Other modded clients poll that relay.
4. New messages are shown locally in chat with the AS7 prefix.

That means:

- it works on servers you do not own
- every player needs the mod installed
- every player needs the same relay URL
- the relay, not Minecraft, is what connects users together

## Current Commands

```text
/as <message>
-# sends private AS chat through your relay

/ast <player>
-# shows player stats

/ast player <player>
-# is an alias for the player lookup

/ast guild <player>
-# shows guild info, rank, last seen, and server when recent

/ast playtime <player>
-# shows last 14 days playtime from your relay/database

/astoggle
-# toggles AS chat rendering on/off

/asconfig toggle
-# does the same as /astoggle

/asconfig toggleshouts
-# hides vanilla shout messages containing shouts:

/asconfig ignore <player>
-# hides AS chats from that player

/asconfig unignore <player>
-# removes that ignore

/asconfig chathistory
-# toggles client session chat logging
-# chat history logs older than 1 day are auto-deleted when logging starts

/asconfig filter
-# opens the local word filter GUI
-# words added there are masked with * in rendered chat
```

## Wynncraft Lookup Notes

`/ast` currently supports:

- player summary
- guild summary
- playtime for the last 14 days

Guild formatting rules:

- if last seen is under 1 minute, it shows `Online`
- if last seen is over 5 minutes, it hides the `Server` line

Login replay rules:

- relay stores up to `200` recent messages
- client only replays the last `20` AS messages on login

## Client Setup

## Requirements

- Minecraft `1.21.11`
- Fabric Loader
- Fabric API
- Java `21+`

Build the mod:

```bash
./gradlew build
```

The mod creates `config/aschat.properties` automatically on first launch.

Example client config:

```properties
auth_token=
chat_history=false
chat_view_mode=BOTH
filtered_words=
hide_shouts=false
ignored_users=
poll_interval_ticks=15
relay_url=
```

Fill in `relay_url` before using `/as` or `/ast playtime`.

## Relay Setup

Relay source is in [relay/AsChatRelayServer.java](relay/AsChatRelayServer.java).

See [relay/README.md](relay/README.md) for the backend setup details.

The relay currently:

- accepts `/messages` for AS chat
- accepts `/playtime` for 14-day playtime lookup
- keeps up to `200` messages in memory
- optionally supports a shared token
- optionally supports a sender allowlist

## Security Notes

This mod does **not** contain obvious malware behavior or remote code execution paths. It is a normal client mod plus a small HTTP relay.

That said, public distribution should still be approached honestly:

- `auth_token` is shared-secret access control, not account-based authentication
- `allow_users` is only a light filter because usernames can be spoofed by modified clients
- using plain `http://` means relay traffic is not encrypted
- session logging is local-only, but if users enable it, chat content is written to disk

Recommended relay hardening:

- use HTTPS in front of the relay
- require a non-empty token
- avoid publishing the token publicly
- add rate limiting if you expect many users

## Chat History Logging

When `/asconfig chathistory` is enabled:

- the mod writes a session log in the Minecraft `logs` folder
- logs older than 1 day are deleted when logging starts
- logging is off by default

## Word Filter

`/asconfig filter` opens a local GUI where players can add or remove filtered words.

Current behavior:

- case-insensitive
- whole-word matching
- masked text is replaced with the same number of `*` characters

Example:

```text
hello fuck there
hello **** there
```

## Wynntils Text Map

The AS7 prefix uses Wynntils-style private-use glyph characters.

Current map used by this project:

```text
a: ""
b: ""
c: ""
d: ""
e: ""
f: ""
g: ""
h: ""
i: ""
j: ""
k: ""
l: ""
m: ""
n: ""
o: ""
p: ""
q: ""
r: ""
s: ""
t: ""
u: ""
v: ""
w: ""
x: ""
y: ""
z: ""
0: ""
1: ""
2: ""
3: ""
4: ""
5: ""
6: ""
7: ""
8: ""
9: ""
pillcornerleft: "⁤"
pillcornerright: ""
pillbg: ""
```

The default AS7 prefix is built from:

```text
a + s + 7
```

Which becomes:

```text

```

If the player is not using the expected Wynntils resources, these may render as unknown glyphs instead of the intended stylized prefix.

## Repo Notes

This GitHub export is intentionally sanitized:

- no live relay IP in defaults
- no packaged jars
- no local deployment files
- no private local config

It is meant to be a clean source repo, not a live server dump.
