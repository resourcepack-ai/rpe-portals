package ai.resourcepack.addons.portals.api.event;

import ai.resourcepack.addons.portals.api.PortalPair;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * A player is walking through a portal. {@link #to()} is where they will come
 * out, already turned to match; change it to send them elsewhere, or cancel to
 * keep them where they are.
 */
public final class PortalTravelEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final PortalPair pair;
    private final boolean intoHere;
    private Location to;
    private boolean cancelled;

    public PortalTravelEvent(Player player, PortalPair pair, boolean intoHere, Location to) {
        super(player);
        this.pair = pair;
        this.intoHere = intoHere;
        this.to = to;
    }

    public PortalPair pair() {
        return pair;
    }

    /** True when they walked into the pair's {@code here} end, false for {@code there}. */
    public boolean intoHere() {
        return intoHere;
    }

    public Location to() {
        return to.clone();
    }

    public void to(Location to) {
        this.to = to.clone();
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
