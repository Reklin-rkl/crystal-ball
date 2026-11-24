# Crystal Ball (Forge 1.18.2)

**alpha-0.35.0** — a Minecraft Forge 1.18.2 mod inspired by the **Crystal Ball (占卜球) from Terraria**:
watch another player through a crystal ball **without leaving your own body**.

Your game mode, inventory, position, hitbox and gravity stay exactly where they are — only the
camera moves to another player. Works at any distance and even across dimensions.

> **Compatibility warning** — this mod changes how the client renders and streams the remote world
> (chunk window, view area, entity streaming). Expect incompatibilities with camera / rendering /
> chunk mods (for example OptiFine or entity culling mods). Bug reports, suggestions, pull requests
> and ports to other Minecraft versions are very welcome.

## Features

- **Camera-only spectating** — you see through the watched player's eyes, your body stays in place
  (and can still be seen, pushed or attacked).
- **Any distance** — terrain, entities and other players around the watched player are streamed to
  your client; you are not limited by your own render distance.
- **Cross dimension** — watching a player in the nether/end from the overworld (and vice versa)
  through an offset copy of the remote world; no portals involved.
- **Entities** — mobs, items, vehicles and other players around the watched player are streamed,
  including their equipment (a skeleton keeps its bow) and the red hurt flash.
- **Multipart bosses** — Twilight Forest's Naga/Hydra and other Forge `PartEntity` multipart mobs
  are re-assembled on the viewer's client (their body parts are synced to the streamed parent).
- **No model in the face** — the watched player's real entity is hidden while its client side clone
  carries the camera, also when switching between several players with the beta key.
- **F5** — first person / third person / front third person all work while watching.
- **Commands** — `/crystalball watch|stop|viewers|list|permission ...` with per command permission
  levels, saved per world.
- **Configurable** — server side `config/crystal_ball-server.toml` and client side
  `config/crystal_ball-client.toml`; `-1` means "no limit".
- **Damage stops watching** — taking damage immediately returns the camera to your body.

## Requirements

- Minecraft 1.18.2
- Forge 40.1.0+ (built against 40.1.0, tested on Forge 40.3.0 and 40.3.12)
- Installed on **both** client and server (it uses its own network channel; both sides must run the
  same mod version, the channel checks a protocol version)

## Installation

1. Install Forge 1.18.2 on the client and the server.
2. Put `crystal_ball-<version>.jar` into `mods/` on both sides.
3. Done.

## Usage

Hold the Crystal Ball and use the alpha/beta keys:

| Key | Default | Action |
| --- | --- | --- |
| α | Left mouse button | **While watching:** return the camera to yourself |
| β | Right mouse button | **While watching or holding the orb:** cycle the camera / start watching |

The orb's key mappings are only active when they should own the mouse button: α while watching,
β while watching or holding the orb. Without the orb, both mouse buttons are completely vanilla, and
with the orb merely held left click is still a normal attack / air swing. The β key still uses right
click while the orb is held (use/place is not available with it in hand; switch to another item for
that).

Both keys can be rebound in `Options → Controls → Key Binds → Crystal Ball`.

Commands:

```
/crystalball watch <player>                  start watching (default permission level 0)
/crystalball stop                            stop watching (default 0)
/crystalball viewers                         list who is watching you (default 0)
/crystalball list                            all watching relations (default level 2)
/crystalball permission get                  show the command permission levels (level 2)
/crystalball permission set <cmd> <0-4>      change them (level 2)
/crystalball permission reset                restore defaults (level 2)
```

## Configuration

Both sides have a config file. The server owns the streaming; the client config caps what that
client accepts. For `entityRadius` and `maxStreamedEntities` the smaller of the two values is used,
and `-1` means "no limit", so the other side decides.

Server side `config/crystal_ball-server.toml`:

| Option | Default | Meaning |
| --- | --- | --- |
| `streamRadius` | 12 | streamed chunk radius around the watched player (4-32) |
| `chunksPerTick` | 24 | chunks pushed per tick while streaming (4-128) |
| `burstChunksPerTick` | 64 | chunks pushed per tick right after a dimension change (16-256) |
| `crossDimensionDistanceChunks` | 1024 | distance of the offset remote world from the viewer's body |
| `entityRadius` | 48 | entity streaming radius in blocks (-1 = no limit, min with the client) |
| `maxStreamedEntities` | 40 | maximum simultaneously streamed entities (-1 = no limit, min with the client) |
| `streamEntitiesSameDimension` | true | also stream far away entities in the same dimension |
| `skipVanillaTrackedEntities` | true | skip entities vanilla already sends to the viewer |
| `blockResendCooldownTicks` | 5 | minimum ticks between two re-pushes of the same chunk |

Client side `config/crystal_ball-client.toml`:

| Option | Default | Meaning |
| --- | --- | --- |
| `entityRadius` | 48 | client cap for the entity streaming radius (-1 = no limit) |
| `maxStreamedEntities` | 40 | client cap for the streamed entity count (-1 = no limit) |

The client values are sent to the server when joining and again whenever the file is reloaded.

## Diagnostics

Every game instance writes its own append-only log, because Minecraft's `logs/latest.log` is shared
by all clients launched from the same game directory (only the last session survives there):

- `logs/crystal_ball-client-<name>-<uuid>.log` — session state (target, dimension, cross dimension),
  camera anchor, camera activation, F5 changes, every other player entity that enters or leaves the
  client level (position, invisible/removed flags, anchor/streamed markers), large position jumps,
  the "hide real player" decisions and a five second status line.
- `logs/crystal_ball-server.log` — player join/leave/dimension/death, session start and stop with the
  reason, per second stream status (viewer, target, dimensions, chunk center, offsets, streamed
  entities) and every streamed entity add/remove.

Both files are safe to attach to a bug report. The visible body in a third person view while
watching is the client side clone of the watched player (it keeps the target's name tag), not another
player.

## Building

Requirements: JDK 17.

```bash
./gradlew build
```

The reobfuscated mod jar is written to `build/libs/crystal_ball-<version>.jar`.

## License

**CC BY-NC 4.0** (Creative Commons Attribution-NonCommercial 4.0 International).
Free to use, modify and redistribute with attribution, **commercial use is not allowed**.
See [LICENSE](LICENSE).

## Credits

- **Reklin**, **DeepSeek Harness AI** — development
- Inspired by the **Crystal Ball (占卜球)** item from **Terraria**
- Technical references: vanilla spectator camera handling, Freecam (camera entity technique),
  Immersive Portals (dimension rendering concepts; this mod does not depend on it)

## Contributing

Compatibility with this mod is not guaranteed to be good — it changes how the client renders and
streams a remote world. If you find a bug, a rendering glitch or an incompatibility, please open an
issue with:

- the matching `logs/crystal_ball-client-<name>-<uuid>.log` and `logs/crystal_ball-server.log`,
- the mod list of both sides,
- a short description (same dimension or cross dimension, F5 perspective, distance, ...).

Pull requests, improvements and ports to other Minecraft/Forge versions are welcome.
