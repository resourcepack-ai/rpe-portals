package ai.resourcepack.addons.portals.portal;

import ai.resourcepack.addons.portals.api.PortalOptions;
import ai.resourcepack.addons.portals.api.PortalPlace;
import ai.resourcepack.addons.portals.api.event.PortalOpenEvent;
import ai.resourcepack.addons.portals.api.event.PortalRemoveEvent;
import ai.resourcepack.addons.portals.api.event.PortalToggleEvent;
import ai.resourcepack.addons.portals.config.Settings;
import ai.resourcepack.addons.portals.window.WindowMaps;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.Easing;
import ai.resourcepack.engine.api.ShaderObject;
import ai.resourcepack.engine.api.ShaderObjects;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every pair, and the clock that opens, loops, shuts and takes them away.
 *
 * <p>Portals live as long as the server does: stopping the plugin takes them
 * all away, so nothing is left standing in a world with no plugin to drive it.
 * Every change a player, a command or another plugin asks for comes through
 * here, and here is where the API's events are fired.
 */
public final class Portals implements org.bukkit.event.Listener {

    /** Every ring this plugin puts down carries this, so a stray one can be told from a live one. */
    public static final String RING_TAG = "portals_ring";

    /** How long the shader takes to spin a portal open, and to pinch it shut. */
    private static final int OPENING_TICKS = 30;
    private static final int CLOSING_TICKS = 22;

    private final Plugin plugin;
    private final ShaderObjects objects;
    private Settings settings;
    private final List<Pair> pairs = new ArrayList<>();
    private WindowMaps windows;

    public Portals(Plugin plugin, ShaderObjects objects, Settings settings) {
        this.plugin = plugin;
        this.objects = objects;
        this.settings = settings;
    }

    public void windows(WindowMaps windows) {
        this.windows = windows;
    }

    public void settings(Settings settings) {
        this.settings = settings;
    }

    public Settings settings() {
        return settings;
    }

    public List<Pair> pairs() {
        return Collections.unmodifiableList(pairs);
    }

    public List<Pair> ownedBy(UUID owner) {
        List<Pair> mine = new ArrayList<>();
        for (Pair pair : pairs) {
            if (pair.owner().equals(owner)) mine.add(pair);
        }
        return mine;
    }

    /** Every portal a player could be looking through or walking into. */
    public List<Portal> portals() {
        List<Portal> all = new ArrayList<>();
        for (Pair pair : pairs) all.addAll(pair.both());
        return all;
    }

    /** The pair a portal belongs to. */
    public Optional<Pair> pairOf(Portal portal) {
        for (Pair pair : pairs) {
            if (pair.here() == portal || pair.there() == portal) return Optional.of(pair);
        }
        return Optional.empty();
    }

    /** The size a pair is drawn at: the option, or the config's, kept to what the window can draw. */
    double sizeOf(PortalOptions options) {
        double size = options.size() > 0 ? options.size() : settings.size();
        return Math.max(PortalOptions.MIN_SIZE, Math.min(PortalOptions.MAX_SIZE, size));
    }

    /** Where a portal of this size stands: its middle is lifted so its sparks just clear the ground. */
    public static Frame frameOf(PortalPlace place, double size) {
        return Frame.standing(place.ground(), place.yaw(), size / 2 * 0.89);
    }

    /**
     * Opens a pair. Empty when a listener cancelled it.
     *
     * @throws IllegalStateException when the portal's object is not known to
     *         the engine, which means its content is not installed or not loaded
     */
    public Optional<Pair> open(PortalPlace herePlace, PortalPlace therePlace, PortalOptions options, Player by) {
        PortalOpenEvent event = new PortalOpenEvent(herePlace, therePlace, options.owner(), by);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return Optional.empty();
        Optional<ContentId> id = ContentId.parse(settings.object());
        if (id.isEmpty()) throw new IllegalStateException("portal.object in config.yml is not an object id");
        double size = sizeOf(options);
        Frame here = frameOf(herePlace, size);
        Frame there = frameOf(therePlace, size);
        Optional<ShaderObject> a = place(here, id.get(), size);
        if (a.isEmpty()) throw new IllegalStateException("RP Engine has no object " + id.get() + " to draw a portal with");
        Optional<ShaderObject> b = place(there, id.get(), size);
        if (b.isEmpty()) {
            a.get().remove();
            throw new IllegalStateException("RP Engine has no object " + id.get() + " to draw a portal with");
        }
        double ring = size / 2 * 0.8;
        Portal pa = new Portal(here, a.get(), ring);
        Portal pb = new Portal(there, b.get(), ring);
        int lifetime = options.lifetime() < 0 ? settings.lifetimeTicks() : options.lifetime();
        Pair pair = new Pair(this, options.owner(), pa, pb, size, Bukkit.getCurrentTick(), lifetime,
                options.sounds(), options.travel(), options.window());
        pairs.add(pair);
        for (Portal p : pair.both()) hold(p.frame(), true);
        if (windows != null && pair.window()) {
            for (Portal p : pair.both()) windows.open(p);
        }
        if (options.startShut()) {
            pair.shut = true;
            for (Portal p : pair.both()) p.phase(Portal.Phase.CLOSED);
        } else {
            pair.loopTick = Bukkit.getCurrentTick() + settings.loopFirstTicks();
            for (Portal p : pair.both()) {
                p.phase(Portal.Phase.OPENING);
                sound(pair, p, settings.soundOpen());
            }
            settleOpen(pair);
        }
        return Optional.of(pair);
    }

    private Optional<ShaderObject> place(Frame frame, ContentId id, double size) {
        Optional<ShaderObject> placed = objects.spawn(frame.middle(), id, size);
        placed.ifPresent(o -> {
            // Never saved with the world: portals live as long as the server does,
            // and a ring that outlived its pair came back unturned, as a duplicate.
            o.entity().setPersistent(false);
            o.entity().addScoreboardTag(RING_TAG);
            o.turnTo(frame.yaw(), 0, 0, 0, Easing.LINEAR);
            o.settle();
        });
        return placed;
    }

    private void settleOpen(Pair pair) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!pair.shut && pairs.contains(pair) && pair.here().phase() == Portal.Phase.OPENING) {
                for (Portal p : pair.both()) p.phase(Portal.Phase.OPEN);
            }
        }, OPENING_TICKS);
    }

    /** Open to shut, shut to open. */
    public void toggle(Pair pair, Player by) {
        if (pair.shut) reopen(pair, by);
        else close(pair, by);
    }

    /** Pinches a pair shut, down to two sparks that stay where they were. */
    public void close(Pair pair, Player by) {
        if (pair.shut || !pairs.contains(pair)) return;
        PortalToggleEvent event = new PortalToggleEvent(pair, false, by);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return;
        shutNow(pair);
    }

    private void shutNow(Pair pair) {
        pair.shut = true;
        for (Portal p : pair.both()) {
            p.phase(Portal.Phase.CLOSING);
            stopLoop(p);
            sound(pair, p, settings.soundClose());
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (pair.shut && pairs.contains(pair) && pair.here().phase() == Portal.Phase.CLOSING) {
                for (Portal p : pair.both()) p.phase(Portal.Phase.CLOSED);
            }
        }, CLOSING_TICKS);
    }

    /** Spins a shut pair open again. */
    public void reopen(Pair pair, Player by) {
        if (!pair.shut || !pairs.contains(pair)) return;
        PortalToggleEvent event = new PortalToggleEvent(pair, true, by);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return;
        pair.shut = false;
        pair.loopTick = Bukkit.getCurrentTick() + settings.loopFirstTicks();
        for (Portal p : pair.both()) {
            p.phase(Portal.Phase.OPENING);
            sound(pair, p, settings.soundOpen());
        }
        settleOpen(pair);
    }

    /** Takes a pair away for good: shut first if it is open, then gone. */
    public void takeAway(Pair pair, Player by) {
        if (!pairs.contains(pair) || pair.removed) return;
        PortalRemoveEvent event = new PortalRemoveEvent(pair, by);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return;
        pair.removed = true;
        if (pair.shut && pair.here().phase() == Portal.Phase.CLOSED) {
            remove(pair);
            return;
        }
        if (!pair.shut) shutNow(pair);
        Bukkit.getScheduler().runTaskLater(plugin, () -> remove(pair), CLOSING_TICKS);
    }

    /** Takes every pair away at once, with no animation: for the plugin going away. */
    public void closeAllNow() {
        for (Pair pair : new ArrayList<>(pairs)) {
            for (Portal p : pair.both()) stopLoop(p);
            pair.removed = true;
            remove(pair);
        }
    }

    public void closeAll(Player by) {
        for (Pair pair : new ArrayList<>(pairs)) takeAway(pair, by);
    }

    private void remove(Pair pair) {
        if (!pairs.remove(pair)) return;
        pair.removed = true;
        for (Portal p : pair.both()) {
            if (windows != null) windows.close(p);
            p.remove();
            hold(p.frame(), false);
        }
    }

    /**
     * Takes away every ring standing in the loaded worlds that belongs to no
     * live pair: rings saved by an older version, or left by a crash.
     */
    public int sweep() {
        java.util.Set<java.util.UUID> live = new java.util.HashSet<>();
        for (Pair pair : pairs) {
            for (Portal p : pair.both()) live.add(p.object().entity().getUniqueId());
        }
        int n = 0;
        for (ShaderObject o : objects.loaded()) {
            if (isStray(o, live)) {
                o.remove();
                n++;
            }
        }
        return n;
    }

    private boolean isStray(ShaderObject o, java.util.Set<java.util.UUID> live) {
        if (live.contains(o.entity().getUniqueId())) return false;
        if (o.entity().getScoreboardTags().contains(RING_TAG)) return true;
        Optional<ContentId> ours = ContentId.parse(settings.object());
        return ours.isPresent() && o.id().equals(ours.get());
    }

    /** A chunk's entities coming back from disk may hold a ring an older version saved. */
    @org.bukkit.event.EventHandler
    public void onEntitiesLoad(org.bukkit.event.world.EntitiesLoadEvent event) {
        java.util.Set<java.util.UUID> live = null;
        for (org.bukkit.entity.Entity e : event.getEntities()) {
            if (!(e instanceof org.bukkit.entity.ItemDisplay)) continue;
            Optional<ShaderObject> o = objects.at(e);
            if (o.isEmpty()) continue;
            if (live == null) {
                live = new java.util.HashSet<>();
                for (Pair pair : pairs) {
                    for (Portal p : pair.both()) live.add(p.object().entity().getUniqueId());
                }
            }
            if (isStray(o.get(), live)) o.get().remove();
        }
    }

    /**
     * Keeps a ring standing and turned. A ring put down in a chunk whose
     * entities have not loaded yet (the far end, or anything opened before a
     * player is near) is swapped by the game for the copy it loads from disk,
     * and that copy has neither the turn nor the state: it stood unturned
     * beside its window. So once a second each ring is checked, picked up again
     * if it was swapped, and turned and set again if it lost either.
     */
    private void keepRing(Portal p) {
        ShaderObject o = p.object();
        if (o == null || !o.isValid()) {
            o = null;
            for (ShaderObject near : objects.near(p.frame().middle(), 0.75)) {
                if (near.entity().getScoreboardTags().contains(RING_TAG) || near.id().toString().equals(settings.object())) {
                    o = near;
                    break;
                }
            }
            if (o == null) return;
            o.entity().setPersistent(false);
            o.entity().addScoreboardTag(RING_TAG);
            p.object(o);
            o.state(p.stateName());
        }
        org.joml.Quaternionf want = new org.joml.Quaternionf().rotateY((float) Math.toRadians(-p.frame().yaw()));
        org.joml.Quaternionf have = o.entity().getTransformation().getLeftRotation();
        if (Math.abs(want.dot(have)) < 0.999f) {
            o.turnTo(p.frame().yaw(), 0, 0, 0, Easing.LINEAR);
        }
    }

    /** The hum and the lifetime: once a tick. */
    public void tick() {
        int now = Bukkit.getCurrentTick();
        for (Pair pair : new ArrayList<>(pairs)) {
            if (pair.removed) continue;
            if (now % 20 == 0) {
                for (Portal p : pair.both()) keepRing(p);
            }
            if (pair.lifetime > 0 && now - pair.openedTick >= pair.lifetime) {
                takeAway(pair, null);
                continue;
            }
            if (pair.shut) continue;
            if (now >= pair.loopTick) {
                pair.loopTick = now + settings.loopEveryTicks();
                for (Portal p : pair.both()) sound(pair, p, settings.soundLoop());
            }
        }
    }

    private void sound(Pair pair, Portal p, String key) {
        if (!pair.sounds || key == null || key.isBlank()) return;
        Location at = p.frame().middle();
        at.getWorld().playSound(at, key, SoundCategory.MASTER, settings.soundVolume(), 1f);
    }

    private void stopLoop(Portal p) {
        String key = settings.soundLoop();
        if (key == null || key.isBlank()) return;
        double reach = 16 * Math.max(1, settings.soundVolume()) + 8;
        Location at = p.frame().middle();
        for (Player player : at.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(at) <= reach * reach) {
                player.stopSound(key, SoundCategory.MASTER);
            }
        }
    }

    /**
     * Keeps the chunks round a portal loaded while it stands, so the window on
     * the far side never asks for a chunk that is not there.
     */
    private void hold(Frame frame, boolean on) {
        World world = frame.world();
        Location middle = frame.middle();
        int cx = middle.getBlockX() >> 4;
        int cz = middle.getBlockZ() >> 4;
        // The window reaches 63 blocks out and 32 to each side.
        int r = 5;
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                if (on) world.addPluginChunkTicket(x, z, plugin);
                else world.removePluginChunkTicket(x, z, plugin);
            }
        }
    }
}
