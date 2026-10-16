package ai.resourcepack.addons.portals.place;

import ai.resourcepack.addons.portals.api.PortalOptions;
import ai.resourcepack.addons.portals.api.PortalPlace;
import ai.resourcepack.addons.portals.config.Settings;
import ai.resourcepack.addons.portals.portal.Pair;
import ai.resourcepack.addons.portals.portal.Portal;
import ai.resourcepack.addons.portals.portal.Portals;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Optional;

/**
 * Placing portals by command: {@code /portals mark} remembers where a
 * player's portals lead (kept on the player, so it survives a restart), and
 * {@code /portals place} opens a pair in front of them that leads there.
 */
public final class Placer {

    public static final String MARK = "portals.mark";
    public static final String PLACE = "portals.place";
    /** Choosing a size other than the config's. */
    public static final String PLACE_SIZE = "portals.place.size";
    /** More pairs at once than {@code pairs-per-player}. */
    public static final String PLACE_UNLIMITED = "portals.place.unlimited";
    /** A destination in another world than the one the player stands in. */
    public static final String PLACE_OTHER_WORLD = "portals.place.otherworld";

    /** What a placing came to. */
    public enum Result { PLACED, NO_DESTINATION, OTHER_WORLD, TOO_CLOSE, NOT_LOADED, CANCELLED }

    private final Portals portals;
    private final NamespacedKey destinationKey;
    private Settings settings;

    public Placer(Plugin plugin, Portals portals, Settings settings) {
        this.portals = portals;
        this.settings = settings;
        this.destinationKey = new NamespacedKey(plugin, "destination");
    }

    public void settings(Settings settings) {
        this.settings = settings;
    }

    /** Where a player's portals lead: here, coming out facing the way they face. */
    public void mark(Player player) {
        setDestination(player, player.getLocation(), player.getLocation().getYaw());
    }

    public void setDestination(Player player, Location ground, float yaw) {
        String value = ground.getWorld().getName() + ";" + ground.getBlockX() + ";" + ground.getBlockY() + ";"
                + ground.getBlockZ() + ";" + yaw;
        player.getPersistentDataContainer().set(destinationKey, PersistentDataType.STRING, value);
    }

    public Optional<PortalPlace> destination(Player player) {
        String value = player.getPersistentDataContainer().get(destinationKey, PersistentDataType.STRING);
        if (value == null) return Optional.empty();
        String[] p = value.split(";");
        if (p.length != 5) return Optional.empty();
        World world = Bukkit.getWorld(p[0]);
        if (world == null) return Optional.empty();
        try {
            Location ground = new Location(world, Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
            return Optional.of(PortalPlace.standing(ground, Float.parseFloat(p[4])));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Opens a pair in front of {@code player}, facing them, leading to their
     * mark. {@code size} 0 is the config's. At their limit, their oldest pair is
     * taken away first, unless they may have as many as they like.
     */
    public Result place(Player player, double size) {
        Optional<PortalPlace> there = destination(player);
        if (there.isEmpty()) return Result.NO_DESTINATION;
        if (there.get().ground().getWorld() != player.getWorld() && !player.hasPermission(PLACE_OTHER_WORLD)) {
            return Result.OTHER_WORLD;
        }
        Location feet = player.getLocation();
        float yaw = Math.round(feet.getYaw() / 90f) * 90f;
        double rad = Math.toRadians(yaw);
        Location ahead = feet.clone().add(-Math.sin(rad) * settings.distance(), 0, Math.cos(rad) * settings.distance());
        // Facing back at the player, so they walk into its front.
        PortalPlace here = PortalPlace.standing(standable(ahead.getBlock()).getLocation(), yaw + 180f);
        Location a = here.ground();
        Location b = there.get().ground();
        double across = size > 0 ? size : settings.size();
        if (a.getWorld() == b.getWorld() && a.distanceSquared(b) < across * across) return Result.TOO_CLOSE;
        if (!player.hasPermission(PLACE_UNLIMITED)) {
            List<Pair> mine = portals.ownedBy(player.getUniqueId());
            while (mine.size() >= settings.pairsPerPlayer()) {
                portals.takeAway(mine.remove(0), player);
            }
        }
        try {
            Optional<Pair> pair = portals.open(here, there.get(),
                    PortalOptions.builder().owner(player.getUniqueId()).size(size).build(), player);
            return pair.isPresent() ? Result.PLACED : Result.CANCELLED;
        } catch (IllegalStateException e) {
            return Result.NOT_LOADED;
        }
    }

    /**
     * The pair a player means: the one whose end they are looking at, else the
     * one with an end nearest them, within {@code reach} blocks.
     */
    public Optional<Pair> nearest(Player player, double reach) {
        Location eye = player.getEyeLocation();
        org.bukkit.util.Vector look = eye.getDirection();
        Pair best = null;
        double bestScore = Double.MAX_VALUE;
        for (Pair pair : portals.pairs()) {
            for (Portal end : pair.both()) {
                Location middle = end.middle();
                if (middle.getWorld() != eye.getWorld()) continue;
                org.bukkit.util.Vector to = middle.toVector().subtract(eye.toVector());
                double distance = to.length();
                if (distance > reach) continue;
                // Looking straight at one counts for a lot more than standing nearer another.
                double aim = distance < 1e-6 ? 1 : to.clone().multiply(1 / distance).dot(look);
                double score = distance * (1.5 - aim);
                if (score < bestScore) {
                    bestScore = score;
                    best = pair;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** The first open space at or near {@code at} with something under it: where a portal can stand. */
    private static Block standable(Block at) {
        Block b = at;
        for (int i = 0; i < 3 && !b.isPassable(); i++) b = b.getRelative(0, 1, 0);
        for (int i = 0; i < 4 && b.getRelative(0, -1, 0).isPassable(); i++) b = b.getRelative(0, -1, 0);
        return b;
    }
}
