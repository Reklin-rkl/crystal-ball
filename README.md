# Crystal Ball (Forge 1.18.2)

**alpha-0.30.0** — a Minecraft Forge 1.18.2 mod inspired by the **Crystal Ball (占卜球) from Terraria**:
watch another player through a crystal ball **without leaving your own body**.

Your game mode, inventory, position, hitbox and gravity stay exactly where they are — only the
camera moves to another player. Works at any distance and even across dimensions.

> **Compatibility warning** — this mod hooks deeply into rendering, chunk streaming and dimension
> handling (it references Immersive Portals, which is itself known for low compatibility). Expect
> incompatibilities with other camera / rendering / chunk mods. Bug reports, suggestions, pull
> requests and ports to other Minecraft versions are very welcome.

## Features

- **Camera-only spectating** — you see through the watched player's eyes, your body stays in place
  (and can still be seen, pushed or attacked).
- **Any distance** — terrain, entities and other players around the watched player are streamed to
  your client; you are not limited by your own render distance.
- **Cross dimension** — watching a player in the nether/end from the overworld (and vice versa)
  through an offset copy of the remote world; no portals involved.
- **Entities** — mobs, items, vehicles and other players around the watched player are streamed.
- **F5** — first person / third person / front third person all work while watching.
- **Commands** — `/crystalball watch|stop|viewers|list|permission ...` with per command permission
  levels, saved per world.
- **Configurable** — server side `config/crystal_ball-server.toml`.
- **Damage stops watching** — taking damage immediately returns the camera to your body.

## Requirements

- Minecraft 1.18.2
- Forge 40.1.0+ (built against 40.1.0, tested on 40.3.12)
- Installed on **both** client and server (it uses its own network channel)

## Installation

1. Install Forge 1.18.2 on the client and the server.
2. Put `crystal_ball-<version>.jar` into `mods/` on both sides.
3. Done.

## Usage

Hold the Crystal Ball and use the alpha/beta keys:

| Key | Default | Action |
| --- | --- | --- |
| α | Left mouse button | Return the camera to yourself |
| β | Right mouse button | Cycle the camera through the other players |

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

Server side `config/crystal_ball-server.toml`:

| Option | Default | Meaning |
| --- | --- | --- |
| `streamRadius` | 12 | streamed chunk radius around the watched player (4-32) |
| `chunksPerTick` | 24 | chunks pushed per tick while streaming (4-128) |
| `burstChunksPerTick` | 64 | chunks pushed per tick right after a dimension change (16-256) |
| `crossDimensionDistanceChunks` | 1024 | distance of the offset remote world from the viewer's body |
| `entityRadius` | 48 | entity streaming radius in blocks (8-128) |
| `maxStreamedEntities` | 40 | maximum simultaneously streamed entities (0-200) |
| `streamEntitiesSameDimension` | true | also stream far away entities in the same dimension |
| `skipVanillaTrackedEntities` | true | skip entities vanilla already sends to the viewer |
| `blockResendCooldownTicks` | 5 | minimum ticks between two re-pushes of the same chunk |

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
  Immersive Portals (dimension rendering concepts)

## Contributing

Compatibility with this mod is not guaranteed to be good — it changes how the client renders and
streams a remote world. If you find a bug, a rendering glitch or an incompatibility, please open an
issue with:

- the full `logs/latest.log` (from both client and server),
- the mod list of both sides,
- a short description (same dimension or cross dimension, F5 perspective, distance, ...).

Pull requests, improvements and ports to other Minecraft/Forge versions are welcome.
