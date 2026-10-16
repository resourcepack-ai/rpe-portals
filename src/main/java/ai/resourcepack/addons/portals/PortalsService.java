package ai.resourcepack.addons.portals;

import ai.resourcepack.addons.portals.api.PortalOptions;
import ai.resourcepack.addons.portals.api.PortalPair;
import ai.resourcepack.addons.portals.api.PortalPlace;
import ai.resourcepack.addons.portals.api.PortalsApi;
import ai.resourcepack.addons.portals.portal.Pair;
import ai.resourcepack.addons.portals.portal.Portal;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** The API other plugins get from the services manager: a thin face on {@link ai.resourcepack.addons.portals.portal.Portals}. */
final class PortalsService implements PortalsApi {

    private final PortalsPlugin plugin;

    PortalsService(PortalsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public Optional<PortalPair> open(PortalPlace here, PortalPlace there, PortalOptions options) {
        Objects.requireNonNull(here, "here");
        Objects.requireNonNull(there, "there");
        Objects.requireNonNull(options, "options");
        return plugin.portals().open(here, there, options, null).map(p -> p);
    }

    @Override
    public List<PortalPair> pairs() {
        return List.copyOf(plugin.portals().pairs());
    }

    @Override
    public List<PortalPair> pairsOwnedBy(UUID owner) {
        return List.copyOf(plugin.portals().ownedBy(owner));
    }

    @Override
    public Optional<PortalPair> pair(UUID id) {
        for (Pair pair : plugin.portals().pairs()) {
            if (pair.id().equals(id)) return Optional.of(pair);
        }
        return Optional.empty();
    }

    @Override
    public Optional<PortalPair> pairNear(Location near, double radius) {
        PortalPair best = null;
        double bestSq = radius * radius;
        for (Pair pair : plugin.portals().pairs()) {
            for (Portal end : pair.both()) {
                Location middle = end.middle();
                if (middle.getWorld() != near.getWorld()) continue;
                double sq = middle.distanceSquared(near);
                if (sq <= bestSq) {
                    bestSq = sq;
                    best = pair;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    @Override
    public void setDestination(Player player, Location ground, float yaw) {
        plugin.placer().setDestination(player, ground, yaw);
    }
}
