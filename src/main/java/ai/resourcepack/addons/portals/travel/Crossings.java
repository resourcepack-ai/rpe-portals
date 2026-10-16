package ai.resourcepack.addons.portals.travel;

import ai.resourcepack.addons.portals.api.event.PortalTravelEvent;
import ai.resourcepack.addons.portals.portal.Frame;
import ai.resourcepack.addons.portals.portal.Pair;
import ai.resourcepack.addons.portals.portal.Portal;
import ai.resourcepack.addons.portals.portal.Portals;
import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.permissions.Permissible;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Walking through: the moment a player's eye passes through the hole from the
 * front, they are put the same distance in front of the partner, turned with
 * it, still moving as fast as they were.
 *
 * <p>The eye, not the feet, because the eye is the camera: the window is what
 * the camera sees until it reaches the plane, and the far side is what it sees
 * after, so going through looks like nothing happened at all. Paper's relative
 * velocity flags keep the speed and turn it with the player, which an ordinary
 * teleport would zero.
 */
public final class Crossings implements Listener {

    public static final String PERMISSION = "portals.travel";
    /** A player who has just come through is left alone for this long, so the far side cannot throw them back. */
    private static final int COOLDOWN_TICKS = 4;

    private final Portals portals;
    private final Map<UUID, Integer> lastCrossed = new HashMap<>();

    public Crossings(Portals portals) {
        this.portals = portals;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getWorld() != to.getWorld()) return;
        if (!may(player)) return;
        Integer last = lastCrossed.get(player.getUniqueId());
        int now = Bukkit.getCurrentTick();
        if (last != null && now - last < COOLDOWN_TICKS) return;
        double eye = player.getEyeHeight();
        for (Pair pair : portals.pairs()) {
            if (!pair.travel()) continue;
            for (Portal portal : pair.both()) {
                Frame here = portal.frame();
                if (here.world() != to.getWorld()) continue;
                double r = portal.radius() - 0.15;
                if (r < 0.4) continue;
                double c0 = here.c(from.getX(), from.getZ());
                double c1 = here.c(to.getX(), to.getZ());
                if (!(c0 > 0 && c1 <= 0)) continue;
                // Where the eye went through the plane.
                double s = c0 / (c0 - c1);
                double x = from.getX() + (to.getX() - from.getX()) * s;
                double y = from.getY() + eye + (to.getY() - from.getY()) * s;
                double z = from.getZ() + (to.getZ() - from.getZ()) * s;
                double a = here.a(x, z);
                double b = here.b(y);
                if (a * a + b * b >= r * r) continue;
                lastCrossed.put(player.getUniqueId(), now);
                carry(player, to, pair, portal);
                return;
            }
        }
    }

    private static boolean may(Permissible who) {
        return who.hasPermission(PERMISSION);
    }

    private void carry(Player player, Location to, Pair pair, Portal portal) {
        Frame here = portal.frame();
        Frame there = portal.partner().frame();
        Vector at = here.carry(there, to.getX(), to.getY(), to.getZ());
        float yaw = to.getYaw() + here.turnTo(there);
        Location carried = new Location(there.world(), at.getX(), at.getY(), at.getZ(), yaw, to.getPitch());
        PortalTravelEvent event = new PortalTravelEvent(player, pair, portal == pair.here(), carried);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return;
        Location target = event.to();
        if (target.getWorld() == player.getWorld()) {
            player.teleport(target,
                    TeleportFlag.Relative.VELOCITY_X,
                    TeleportFlag.Relative.VELOCITY_Y,
                    TeleportFlag.Relative.VELOCITY_Z,
                    TeleportFlag.Relative.VELOCITY_ROTATION);
        } else {
            Vector velocity = here.carryDirection(there, player.getVelocity());
            player.teleport(target);
            player.setVelocity(velocity);
        }
    }

    public void forget(UUID player) {
        lastCrossed.remove(player);
    }
}
