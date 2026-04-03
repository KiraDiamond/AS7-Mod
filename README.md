# AS Chat

AS Chat is a client-only Fabric mod for Minecraft `1.21.11`.

It adds:
- a private `/as` chat channel
- Wynntils item sharing
- image embeds with hover previews and map rendering
- local chat filters and chat-history logging
- Wynncraft helper lookups through `/ast`

This public mirror is intentionally client-only. The bundled relay/backend source is not included here.

## Credits

- Topdiamond2006
- Codex

## Main Commands

```text
/as <message>
/ast <player>
/ast player <player>
/ast guild <player>
/ast playtime <player>
/astoggle
/asconfig toggle
/asconfig toggleshouts
/asconfig chathistory
/asconfig filter
/asconfig ignore <player>
/asconfig unignore <player>
/asconfig image <name> <link>
```

## Item Sharing

If Wynntils is installed:

- use `/as <item>` or `/as [item]`
- the held item is encoded locally
- receiving clients can decode it into a hoverable item entry

## Image Sharing

AS Chat supports image embeds and image aliases.

Examples:

```text
/as https://example.com/image.png
/asconfig image catgirl https://example.com/image.png
/as :catgirl:
```

The client can:

- preview embeds on hover
- render images into Minecraft map data
- cache image previews client-side

## Build

Requirements:

- Minecraft `1.21.11`
- Fabric Loader
- Fabric API
- Java `21+`

Build with:

```bash
./gradlew build
```

## Config

The mod creates `config/aschat.properties` on first launch.

Relevant keys:

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

`relay_url` is still required if you want the private AS chat relay features or `/ast playtime`.

## Notes

- No live relay IPs are committed here.
- No packaged jars are committed here.
- No local deployment or private config files are included here.
