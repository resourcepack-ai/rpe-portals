package ai.resourcepack.addons.portals.api.event;

import ai.resourcepack.addons.portals.api.PortalPair;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** A pair is about to be shut down to a small glowing ball, or opened from one. Cancel it to leave it as it is. */
public final class PortalToggleEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final PortalPair pair;
    private final boolean opening;
    private final Player player;
    private boolean cancelled;

    public PortalToggleEvent(PortalPair pair, boolean opening, Player player) {
        this.pair = pair;
        this.opening = opening;
        this.player = player;
    }

    public PortalPair pair() {
        return pair;
    }

    /** True when it is being opened, false when it is being shut. */
    public boolean opening() {
        return opening;
    }

    /** The player who clicked it, or null for a command or a plugin. */
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
