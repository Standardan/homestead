# Homestead

A lightweight, persistent **player homes** plugin for Paper 1.21+. Players set named
homes and teleport back to them; everything is stored in a local SQLite database and
survives restarts.



## Download

**[Download the latest release »](https://github.com/Standardan/homestead/releases/latest)**

Drop the `.jar` into your server's `plugins/` folder and restart. Requires Paper 1.21+ (Java 21).

## Features

- `/sethome [name]`, `/home [name]`, `/delhome <name>`, `/homes` (defaults to a home named `home`)
- **Persistent storage** in SQLite — homes survive server restarts
- **Per-rank home limits** via the `homestead.homes.<number>` permission (or `homestead.homes.unlimited`)
- **Teleport cooldowns**, configurable and bypassable by permission
- **Tab-completion** of your own home names, served from an in-memory cache (zero DB hits per keystroke)
- **Clickable home list** — click a home in `/homes` to teleport
- Modern Paper text (MiniMessage) and `teleportAsync` for smooth, lag-free teleports

## Commands & permissions

| Command | Description | Permission |
|---|---|---|
| `/sethome [name]` | Set a home at your location | `homestead.use` (default: all) |
| `/home [name]` | Teleport to a home | `homestead.use` |
| `/delhome <name>` | Delete a home | `homestead.use` |
| `/homes` | List your homes | `homestead.use` |

| Permission | Effect | Default |
|---|---|---|
| `homestead.use` | Use all home commands | everyone |
| `homestead.homes.<n>` | Raise home limit to `<n>` | — |
| `homestead.homes.unlimited` | No home limit | op |
| `homestead.cooldown.bypass` | Skip teleport cooldown | op |

## Configuration (`config.yml`)

```yaml
default-home-limit: 3
teleport-cooldown-seconds: 3
```

## How it works (design notes)

- **Never blocks the main thread.** All SQL runs on a single dedicated background
  thread (`Database`), returning `CompletableFuture`s. Results that touch players or
  the world are marshalled back onto the main thread via the scheduler before use.
- **Layered.** `model` (the `Home` record) / `storage` (`Database` + `HomeRepository`,
  the DAO) / `command` (`HomeCommand`). Each layer has one job.
- **Safe SQL.** Every query is a `PreparedStatement` with bound parameters.
- **Runtime dependencies via Paper's library loader** (see `plugin.yml`), so the
  SQLite driver isn't fat-jarred and the build stays tiny.

## Building

Requires JDK 21 and Maven.

```bash
mvn clean package
# -> target/homestead-1.0.0.jar
```

Drop the jar in your server's `plugins/` folder and restart.
