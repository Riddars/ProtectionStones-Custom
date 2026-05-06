# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build

```powershell
mvn clean install -DskipTests
```

Produces a shaded JAR in `target/` with bundled dependencies (bstats, night-config, commons-io/lang3, json-simple) and relocated `org.bstats` → `dev.espi.protectionstones.metrics`. Java 21 source/target.

No automated test suite — testing requires a running Paper server with WorldGuard and WorldEdit installed.

## Test server (Docker)

Server lives in `C:\server\`. Plugin JARs are in `C:\server\test\plugins\`.

**Versions that work together (verified):**
- Paper **1.21.10** — WorldGuard 7.0.15 has `api-version: 1.21.10` in its plugin.yml, so the server must be >= 1.21.10. Using 1.21.8 causes `NoClassDefFoundError: Flag`.
- WorldGuard **7.0.15** (`worldguard-bukkit-7.0.15.jar`)
- WorldEdit **7.4.2** (`worldedit-bukkit-7.4.2.jar`)
- Vault **1.7.3** (`Vault.jar`)

**`C:\server\docker-compose.yml`:**
```yaml
version: "3.8"
services:
  test:
    image: itzg/minecraft-server:latest
    container_name: test
    ports:
      - "25565:25565"
    environment:
      EULA: "TRUE"
      VERSION: "1.21.10"
      MODE: "survival"
      TYPE: "PAPER"
      ONLINE_MODE: "FALSE"
    volumes:
      - ./test/data:/data
      - ./test/plugins:/data/plugins
    restart: unless-stopped
```

**Deploy cycle (PowerShell):**
```powershell
# из папки репозитория
mvn clean install -DskipTests
Copy-Item -Force target\protectionstones-2.10.5.jar C:\server\test\plugins\protectionstones-2.10.5.jar

# из C:\server
docker compose restart test
docker compose logs -f test
```

When changing Paper version: delete `C:\server\test\data\paper-*.jar` and `C:\server\test\data\cache\mojang_*.jar` before restarting so the new version is downloaded.

OP management for testing:
```powershell
docker compose exec test rcon-cli "op PlayerName"
docker compose exec test rcon-cli "deop PlayerName"
```

Note: OP players bypass WorldGuard region protection by default. Use `/rg bypass` in-game to toggle, or deop test accounts to test real permissions.

## Architecture

**Entry points:**
- `ProtectionStones.java` — main `JavaPlugin` class. `onLoad()` registers WorldGuard custom flags; `onEnable()` wires dependencies, loads config/blocks, registers commands, builds region-name cache, and kicks off legacy data migrations.
- `PSCommand.java` — routes all `/ps` subcommands by dispatching to registered `PSCommandArg` implementations.
- `ListenerClass.java` — single Bukkit `Listener` for player join/tax events, PS region events, and plot orphan cleanup.

**Command system (`commands/`):**
Each subcommand implements `PSCommandArg` (defines names, permissions, flags, tab-complete, and execute logic). Registered in `PSCommand.addDefaultArguments()`. Add new subcommands by implementing `PSCommandArg` and adding `ProtectionStones.getInstance().addCommandArgument(new ArgXxx())` there.

**Region model:**
- `PSRegion` — abstract base with factory methods `fromLocation()`, `fromLocationGroup()`, `fromWGRegion()`, `fromName()`.
- `PSStandardRegion` — wraps a single WorldGuard `ProtectedRegion`.
- `PSGroupRegion` — parent region containing merged children.
- `PSMergedRegion` — polygon region formed by merging multiple standard regions (`WGMerge`).

**Configuration:**
- `PSConfig.java` — TOML config via Night-Config; fields annotated with `@Path` matching `config.toml` keys.
- `PSProtectBlock.java` — one instance per block config file in `blocks/`; holds radius, economy, behavior flags, and crafting recipe.
- `FlagHandler.java` — registers all PS-specific WorldGuard flags (`ps-home`, `ps-block-material`, `ps-merged-regions`, `ps-rent-*`, `ps-tax-*`, `ps-plot`).

**Key utilities:**
- `WGUtils.java` — WorldGuard integration (region creation, flag access, lookup helpers).
- `WGMerge.java` — polygon merge logic combining regions.
- `PSEconomy.java` — Vault adapter for rent and tax transactions.
- `PSL.java` — all player-facing messages (color-code aware). Add new entries as enum values before the closing `;`.
- `UUIDCache.java` — in-memory UUID↔username cache. Use `UUIDCache.containsName(name)` + `UUIDCache.getUUIDFromName(name)` for offline player lookup in commands.
- `utils/upgrade/` — one-time data migration utilities run at startup.

**Custom events (`event/`):**
`PSCreateEvent`, `PSRemoveEvent`, `PSBreakProtectBlockEvent`, `PSObtainRegion`, `PSLoseRegion` — fire at lifecycle moments. `PSRemoveEvent` fires **before** the WG region is removed from the manager.

**Optional integrations:** Vault (economy), PlaceholderAPI (`placeholders/`), LuckPerms (per-group region limits).

## Dependencies (pom.xml)

| Artifact | Version |
|---|---|
| spigot-api | 1.21.8-R0.1-SNAPSHOT |
| worldguard-bukkit | 7.0.15-SNAPSHOT |
| worldedit-bukkit | 7.4.2 |
| Vault API | 1.7 |
| PlaceholderAPI | 2.11.6 |
| LuckPerms API | 5.2 |

External Maven repos: SpigotMC, EngineHub, CodeMC, ExtendedClip, PaperMC.

## Configuration files

Block configs live in `src/main/resources/` (`block1.toml`, etc.) and are loaded from the `blocks/` server folder at runtime. Each defines a protection block type with its own radius, world list, crafting recipe, economy settings, and default/allowed WorldGuard flags.

## Custom feature: Plot system (`/ps plot`)

Implemented in this fork. Allows PS region owners to carve named inner zones (plots) using a WorldEdit wooden-axe selection — no physical block required.

### How plots work

A plot is a plain `ProtectedCuboidRegion` (not a PS region) identified by the custom WG flag `ps-plot` (value = parent PS region ID). It is **not** returned by `PSRegion.fromLocation()` because it lacks `ps-block-material`. Key properties set on creation:
- Bounds: exact Y range from the WE selection (not bedrock-to-sky)
- Priority: parent PS region priority + 10
- WG parent: set to the parent PS region (flag inheritance)
- Owner: the player who created it

### Parent selection logic (`findBestParent` in `ArgPlot`)

Iterates all WG regions in the player's world, keeps only PS regions (`PSRegion.fromWGRegion` != null) that the player owns and that contain all 8 corners of the selection. Returns the one with the **smallest x×z footprint** — i.e., the most specific (innermost) containing region. This correctly handles nested own regions (e.g. 16-block inside 64-block): the innermost that fits the selection becomes the parent.

A selection that spans two non-overlapping regions has no single container → rejected. A selection that extends outside all owned regions → rejected.

### Orphan cleanup

Plots whose parent PS region was deleted without firing `PSRemoveEvent` (e.g. via `/rg remove` in console) are "orphans". Two cleanup passes run:
1. **On every `PSRemoveEvent`** (`ListenerClass.onPSRemoveCascadePlots`): direct children are deleted, then a second pass removes any pre-existing orphans in the same world.
2. **On `ServerLoadEvent`** (`ListenerClass.onServerLoad`): scans all worlds on startup and removes orphans. Results are logged: `[Plots] Cleaned N orphan plot(s) on startup.`

### Commands

| Command | Who can use |
|---|---|
| `/ps plot create [name]` | Plot permission + owns a PS region containing the WE selection |
| `/ps plot delete <name\|id>` | Plot owner OR parent PS region owner OR admin |
| `/ps plot add <name\|id> <player>` | Same as delete |
| `/ps plot kick <name\|id> <player>` | Same as delete |
| `/ps plot list` | Any player with plot permission |

Name uniqueness is enforced per player per world at creation time (case-insensitive). Management commands work from anywhere in the world.

### Files changed for plots

- `FlagHandler.java` — added `PS_PLOT` StringFlag, registered in `registerFlags()`
- `PSConfig.java` — added `plotCreateCost` field (`@Path("plot.create_cost")`)
- `src/main/resources/config.toml` — added `[plot]` section with `create_cost = 1500.0`
- `PSL.java` — added `PLOT_*` message entries
- `commands/ArgPlot.java` — new file, registered in `PSCommand.addDefaultArguments()`
- `ListenerClass.java` — added `onPSRemoveCascadePlots` and `onServerLoad` + `cleanOrphanPlots`

### Permission

`protectionstones.plot` — grant to allow use of all `/ps plot` subcommands. Default: not granted (must be explicitly added via permissions plugin).
