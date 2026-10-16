package ai.resourcepack.addons.portals.api.event;

import ai.resourcepack.addons.portals.api.PortalPair;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A pair is about to be taken away: by a player, a command, its lifetime
 * running out, or a plugin. Cancel it to keep it. Not fired when the server
 * stops, which takes every pair away.
 */
public final class PortalRemoveEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final PortalPair pair;
    private final Player player;
    private boolean cancelled;

    public PortalRemoveEvent(PortalPair pair, Player player) {
        this.pair = pair;
        this.player = player;
    }

    public PortalPair pair() {
        return pair;
    }

    /** The player who took it away, or null. */
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
