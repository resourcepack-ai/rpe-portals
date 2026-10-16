# Portals API

Open portals from your own plugin: anywhere, at any size, open or shut, with
or without travel, sound or the see-through window, and hear about everything
that happens to them.

Add `softdepend: [RPEPortals]` (or `depend`) to your `plugin.yml` and compile
against the Portals jar (`compileOnly`). Everything runs on the main
thread.

## Opening a pair

```java
PortalsApi api = PortalsApi.get();

PortalPair pair = api.open(
        PortalPlace.standing(spawnGround, 180f),   // the block a player would stand in, and the way its front faces
        PortalPlace.standing(arenaGround, 0f),
        PortalOptions.builder()
                .size(9)                 // ring diameter in blocks, 2 to 12
                .lifetimeSeconds(60)     // 0 keeps it until removed
                .sounds(false)
                .build())
        .orElseThrow();                  // empty when a PortalOpenEvent listener cancelled it
```

Walking into the front of one end brings you out of the front of the other,
turned with it and still moving at the same speed. A portal faces one of the
four compass directions: the yaw is snapped to the nearest (0 south, 90 west,
180 north, 270 east). `PortalPlace.facing(ground, viewer)` turns it toward a
location instead.

`open` throws `IllegalStateException` when RP Engine has no portal object to
draw with (the content is not loaded, or the server is older than 1.21.4).

### Options

| Builder method | Default | What it does |
|---|---|---|
| `owner(UUID)` | `PortalOptions.NO_OWNER` | Who it belongs to. A player's own pairs count toward `pairs-per-player` when they use `/portals place`, and they may open, shut and take them away with `portals.toggle` and `portals.remove`. |
| `size(double)` | config `portal.size` | Ring diameter in blocks, clamped to 2 to 12. |
| `sounds(boolean)` | `true` | Opening, closing and hum sounds. |
| `travel(boolean)` | `true` | Whether walking in carries players through. |
| `window(boolean)` | `true` | Whether the far side is seen through the hole. Without it the ring is still drawn. |
| `lifetimeTicks(int)` / `lifetimeSeconds(double)` | config `portal.lifetime` | When it takes itself away. 0 keeps it. |
| `startShut(boolean)` | `false` | Begins as a small glowing ball, to be opened later. |

## Driving a pair

```java
pair.shut();          // pinches down to a small glowing ball that stays where it was
pair.open();          // spins open again
pair.toggle();
pair.travel(false);   // still open and seen through, but solid
pair.sounds(false);
pair.remove();        // shuts it if open, then takes it away for good
pair.isOpen();        // open or opening
pair.isValid();       // false once removed
pair.here().middle(); // each end's centre, facing() and current hole() width
```

Players right-click a portal to open or shut it (`portals.toggle`) and
sneak-right-click to take it away. Those clicks go through the same calls, so
the same events fire.

## Finding pairs

```java
api.pairs();                      // every pair
api.pairsOwnedBy(player.getUniqueId());
api.pair(id);                     // by PortalPair.id()
api.pairNear(location, 5);        // the closest pair with an end within 5 blocks
```

## Where a player's portals lead

```java
api.setDestination(player, ground, yaw);   // as /portals mark does; /portals place then leads there
```

## Events

All in `ai.resourcepack.addons.portals.api.event`. `player()` is the
player who caused it, or `null` when it was a plugin, a command from the
console, or a portal's lifetime running out.

| Event | Cancellable | When |
|---|---|---|
| `PortalOpenEvent` | yes | Before a pair is opened. Has both places and the owner. |
| `PortalToggleEvent` | yes | Before a pair opens or shuts. `opening()` says which. |
| `PortalRemoveEvent` | yes | Before a pair is taken away. Not fired when the server stops. |
| `PortalTravelEvent` | yes | A player is walking through. `to()` is where they will come out; `to(Location)` sends them elsewhere. `intoHere()` says which end they walked into. |

```java
@EventHandler
public void onTravel(PortalTravelEvent event) {
    if (!event.getPlayer().hasPermission("arena.enter")) event.setCancelled(true);
}
```

## Notes

- Pairs do not survive a restart. Open them again in your `onEnable` (after
  Portals has enabled) if they should always be there.
- Each standing pair keeps the chunks around both ends loaded, so the window
  never has to show a chunk that is not there.
- The window shows the blocks up to 63 blocks behind the far end, and a lower
  detail horizon beyond that.
