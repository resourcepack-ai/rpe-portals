package ai.resourcepack.addons.portals.api;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Portals for other plugins: open pairs of portals anywhere, at any size,
 * shut and open them, and hear about everything that happens to them.
 *
 * <pre>
 * PortalsApi portals = PortalsApi.get();
 * PortalPair pair = portals.open(
 *         PortalPlace.standing(spawnGround, 180f),
 *         PortalPlace.standing(arenaGround, 0f),
 *         PortalOptions.builder().size(9).lifetimeSeconds(60).build());
 * pair.shut();
 * </pre>
 *
 * <p>Everything here runs on the main thread.
 */
public interface PortalsApi {

    /** The running plugin's API. Throws when Portals is not enabled. */
    static PortalsApi get() {
        RegisteredServiceProvider<PortalsApi> reg = Bukkit.getServicesManager().getRegistration(PortalsApi.class);
        if (reg == null) throw new IllegalStateException("Portals is not enabled");
        return reg.getProvider();
    }

    /**
     * Opens a pair: walking into {@code here}'s front comes out of
     * {@code there}'s front, and each shows the other's side through its hole.
     *
     * @return the pair, or empty when a listener cancelled it
     * @throws IllegalStateException when the portal's 3D object is not loaded
     *         (the content is not installed, or RP Engine cannot place objects)
     */
    Optional<PortalPair> open(PortalPlace here, PortalPlace there, PortalOptions options);

    /** Every pair, open or shut. */
    List<PortalPair> pairs();

    /** The pairs a player (or a plugin's owner id) opened. */
    List<PortalPair> pairsOwnedBy(UUID owner);

    /** The pair with this id, while it exists. */
    Optional<PortalPair> pair(UUID id);

    /** The nearest pair with an end within {@code radius} blocks of {@code near}. */
    Optional<PortalPair> pairNear(Location near, double radius);

    /** Sets where a player's portals lead, as {@code /portals mark} does: they come out facing {@code yaw}. */
    void setDestination(Player player, Location ground, float yaw);
}
