package ai.resourcepack.addons.portals.api.event;

import ai.resourcepack.addons.portals.api.PortalPlace;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * A pair is about to be opened: by a player's command, a staff command or a
 * plugin. Cancel it and nothing is placed.
 */
public final class PortalOpenEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final PortalPlace here;
    private final PortalPlace there;
    private final UUID owner;
    private final Player player;
    private boolean cancelled;

    public PortalOpenEvent(PortalPlace here, PortalPlace there, UUID owner, Player player) {
        this.here = here;
        this.there = there;
        this.owner = owner;
        this.player = player;
    }

    public PortalPlace here() {
        return here;
    }

    public PortalPlace there() {
        return there;
    }

    public UUID owner() {
        return owner;
    }

    /** The player who placed it, or null for the console, a command block or a plugin. */
    public Player player() {
        return player;
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
