package ai.resourcepack.addons.portals.portal;

import ai.resourcepack.addons.portals.api.PortalPair;

import java.util.List;
import java.util.UUID;

/**
 * Two portals that lead to each other, who opened them, and how they behave.
 * Shut, they stay where they are as two sparks.
 */
public final class Pair implements PortalPair {

    private final Portals portals;
    private final UUID id = UUID.randomUUID();
    private final UUID owner;
    private final Portal here;
    private final Portal there;
    private final double size;
    final int openedTick;
    final int lifetime;
    final boolean window;
    boolean sounds;
    boolean travel;
    int loopTick;
    boolean shut;
    boolean removed;

    Pair(Portals portals, UUID owner, Portal here, Portal there, double size, int openedTick,
         int lifetime, boolean sounds, boolean travel, boolean window) {
        this.portals = portals;
        this.owner = owner;
        this.here = here;
        this.there = there;
        this.size = size;
        this.openedTick = openedTick;
        this.lifetime = lifetime;
        this.sounds = sounds;
        this.travel = travel;
        this.window = window;
        here.pair(there);
        there.pair(here);
    }

    @Override
    public UUID id() {
        return id;
    }

    @Override
    public UUID owner() {
        return owner;
    }

    @Override
    public Portal here() {
        return here;
    }

    @Override
    public Portal there() {
        return there;
    }

    public List<Portal> both() {
        return List.of(here, there);
    }

    @Override
    public double size() {
        return size;
    }

    /** Closing or closed: a spark, not a way through. */
    public boolean isShut() {
        return shut;
    }

    @Override
    public boolean isOpen() {
        return !shut && !removed;
    }

    @Override
    public boolean isValid() {
        return !removed;
    }

    @Override
    public void open() {
        portals.reopen(this, null);
    }

    @Override
    public void shut() {
        portals.close(this, null);
    }

    @Override
    public void toggle() {
        portals.toggle(this, null);
    }

    @Override
    public void remove() {
        portals.takeAway(this, null);
    }

    @Override
    public boolean travel() {
        return travel;
    }

    @Override
    public void travel(boolean on) {
        travel = on;
    }

    @Override
    public boolean sounds() {
        return sounds;
    }

    @Override
    public void sounds(boolean on) {
        sounds = on;
    }

    /** Whether this pair shows the far side through its hole. */
    public boolean window() {
        return window;
    }
}
