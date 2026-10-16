package ai.resourcepack.addons.portals.api;

import org.bukkit.Location;

import java.util.Objects;

/**
 * Where one end of a pair stands: the block a player standing there would be
 * in, and the way its front faces. A portal faces one of the four compass
 * directions (the yaw is snapped to the nearest), because what is seen through
 * it is made of whole blocks.
 */
public record PortalPlace(Location ground, float yaw) {

    public PortalPlace {
        Objects.requireNonNull(ground, "ground");
        Objects.requireNonNull(ground.getWorld(), "ground's world");
    }

    /** A portal standing on {@code ground}, its front looking along {@code yaw} (0 is south, 90 west). */
    public static PortalPlace standing(Location ground, float yaw) {
        return new PortalPlace(ground.clone(), yaw);
    }

    /** A portal standing on {@code ground}, its front turned to face {@code viewer}. */
    public static PortalPlace facing(Location ground, Location viewer) {
        double dx = viewer.getX() - ground.getX();
        double dz = viewer.getZ() - ground.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        return new PortalPlace(ground.clone(), yaw);
    }
}
