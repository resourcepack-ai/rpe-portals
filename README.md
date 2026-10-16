# Portals

Portals you can see through, for Minecraft servers, built on
[RP Engine](https://github.com/resourcepack-ai/rp-engine). A ring of sparks
opens in front of you, and through it is the place it leads to: its blocks, its
sky, its clouds and its light, from wherever you stand. Walk in and you come out
of the other ring, still moving the way you were.

It is an RP Engine addon and uses nothing but the engine's public API
(`ai.resourcepack.engine.api`). The ring is an RP Engine 3D shader object
shipped inside the jar; the window is this plugin's own.

![Looking through a portal and walking through it](https://resourcepack.ai/listings/portals/portal.gif)

## Requirements

- **Spigot or Paper 26.2**, with players on **Java Edition 26.2**. The window
  is drawn by the game's own shaders through a resource pack written for 26.2.
  On Paper a player keeps their speed through a portal in one packet and the far
  land is read in the background; on Spigot the same is done the plain Bukkit
  way (`platform/Platform`).
- **[RP Engine](https://resourcepack.ai/rp-engine) 0.2.11 or newer.**

## Installing

1. Put `RPEngine.jar` and `RPE-portals-<version>.jar` in `plugins/` and start
   the server.
2. Portals installs its content into `plugins/RPEngine/content/portals/` and
   reloads the engine once, so the resource pack the engine serves carries it.
3. On first start it downloads the Minecraft client jar for your server's
   version from Mojang, once, for the block textures it draws in the window.
   `textures.download: false` in `config.yml` turns that off.

## Using it

| Command | What it does | Permission | Default |
|---|---|---|---|
| `/portals mark` | Your portals lead here | `portals.mark` | Everyone |
| `/portals place [size]` | Open a pair in front of you, leading to your mark | `portals.place` (`portals.place.size` for a size) | Everyone (size: ops) |
| `/portals toggle` | Open or shut the portal you are looking at | `portals.toggle` (`.others`) | Everyone (others': ops) |
| `/portals remove [all]` | Take that portal away, or all of yours | `portals.remove` (`.others`) | Everyone (others': ops) |
| `/portals list` | Your pairs and where they lead | `portals.list` | Everyone |
| `/portals open <world> <x y z yaw> <x y z yaw> [size]` | A pair at set places | `portals.open` | Ops |
| `/portals closeall` | Take away every portal | `portals.closeall` | Ops |
| `/portals reload` | Re-read `config.yml` | `portals.reload` | Ops |

Right-clicking a portal opens or shuts it; sneaking and right-clicking takes it
away. Walking through needs `portals.travel`. `portals.place.unlimited` lifts
`pairs-per-player`, and `portals.place.otherworld` lets a mark be in another
world. `portals.player` and `portals.admin` are parents.

The full guide is at
[resourcepack.ai/docs/rp-engine/portals](https://resourcepack.ai/docs/rp-engine/portals),
and the API for other plugins is in [API.md](API.md).

## How the window works

Nothing in the world behind a portal is changed. The plugin reads the far side
(128 blocks across, 63 deep and 28 tall in detail, a coarse height map and
canopy beyond that, the clouds) into the pixels of ordinary map items, hung in
invisible item frames tiling the hole. The text shader the game draws item
frame maps with is replaced by one that recognises those maps by two marker
pixels in their header, and ray-marches the far side out of them for every
pixel of the hole, with the real block textures, light, biome tints and ambient
occlusion. The same maps go to every player: everything that depends on where
you stand is worked out in the shader.

| Package | What it does |
|---|---|
| `portal` | Pairs, their frames (where each end stands and faces) and their lifecycle |
| `place` | Marking a destination and placing pairs by command |
| `travel` | Carrying a player through, speed and turn kept |
| `window` | Reading the far side into maps, the block textures, and writing the shader |
| `api` | The public API and its events |
| `command`, `config` | `/portals` and `config.yml` |
| `platform`, `text` | What differs between Spigot and Paper, and sending MiniMessage on both |

`src/main/resources/window/` holds the shader templates (`text.vsh`, `text.fsh`,
`common.glsl`); the plugin fills in this server's map colour table and writes
them into its content pack at start. `shaders/ring.glsl` is the ring's own
source; the `item.fsh` in the content pack is that, linked into RP Engine's 3D
object framework.

## Building

```bash
./gradlew build
```

The jar is `build/libs/RPE-portals-<version>.jar`. The build compiles against
RP Engine, which it downloads from `cdn.resourcepack.ai` the first time and
checks against a SHA-256. Set `ENGINE_JAR` to compile against a jar of your own.

## Licence

GPL-3.0. See [LICENSE](LICENSE).
