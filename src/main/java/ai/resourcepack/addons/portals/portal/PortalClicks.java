package ai.resourcepack.addons.portals.portal;

import ai.resourcepack.addons.portals.window.WindowMaps;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Optional;

/**
 * Right-clicking a portal opens it or shuts it; sneaking while you do takes it
 * away. The hole is tiled with the window's invisible frames, so a click
 * anywhere on it lands on one, with any item in hand or none.
 */
public final class PortalClicks implements Listener {

    public static final String TOGGLE = "portals.toggle";
    public static final String TOGGLE_OTHERS = "portals.toggle.others";
    public static final String REMOVE = "portals.remove";
    public static final String REMOVE_OTHERS = "portals.remove.others";

    private final Portals portals;
    private final WindowMaps windows;
    private FileConfiguration messages;

    public PortalClicks(Portals portals, WindowMaps windows, FileConfiguration messages) {
        this.portals = portals;
        this.windows = windows;
        this.messages = messages;
    }

    public void messages(FileConfiguration messages) {
        this.messages = messages;
    }

    @EventHandler(ignoreCancelled = false)
    public void onClick(PlayerInteractEntityEvent event) {
        Optional<Portal> portal = windows.portalOf(event.getRightClicked());
        if (portal.isEmpty()) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        Optional<Pair> pair = portals.pairOf(portal.get());
        if (pair.isEmpty()) return;
        Player player = event.getPlayer();
        if (player.isSneaking()) {
            boolean mine = pair.get().owner().equals(player.getUniqueId());
            if (!player.hasPermission(mine ? REMOVE : REMOVE_OTHERS)) {
                say(player, "no-permission");
                return;
            }
            portals.takeAway(pair.get(), player);
            return;
        }
        boolean mine = pair.get().owner().equals(player.getUniqueId());
        if (!player.hasPermission(mine ? TOGGLE : TOGGLE_OTHERS)) {
            say(player, "no-permission");
            return;
        }
        portals.toggle(pair.get(), player);
    }

    /** The frames are part of the portal: a punch does nothing to them. */
    @EventHandler(ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (windows.portalOf(event.getEntity()).isPresent()) event.setCancelled(true);
    }

    private void say(Player player, String key) {
        player.sendMessage(MiniMessage.miniMessage().deserialize(java.util.Objects.requireNonNullElse(messages.getString("messages." + key), key)));
    }
}
