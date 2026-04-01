# AS Chat Relay

Small Java HTTP relay for the client-only AS Chat mod.

## Run

Compile:

```bash
javac -d out relay/AsChatRelayServer.java
```

Run:

```bash
java -cp out relay.AsChatRelayServer
```

Or pass a port:

```bash
java -cp out relay.AsChatRelayServer 8787
```

## Relay Config

Create `relay.properties` next to the jar or source working directory.

Example:

```properties
port=25565
auth_token=
allow_users=
log_messages=true
playtime_db_path=/path/to/wynncraft_playtime.db
playtime_sql=SELECT COALESCE(ROUND(SUM(playtime_hours) * 60, 0), 0) FROM daily_playtime WHERE username = ? COLLATE NOCASE AND date >= date('now', '-13 days')
```

## Client Config

Point clients at your relay:

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

Fill in `relay_url` yourself before distributing.
