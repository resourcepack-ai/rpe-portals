package ai.resourcepack.addons.portals.portal;

import ai.resourcepack.addons.portals.platform.Ticks;
import ai.resourcepack.addons.portals.api.PortalEnd;
import ai.resourcepack.engine.api.ShaderObject;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;

/**
 * One ring of a pair: where it stands, the shader object drawing it, and how
 * far open it is.
 *
 * <p>How open it is has to be worked out here as well as in the shader, from
 * the same curves and the same clock, because the window the plugin sends has
 * to be the size of the hole the shader draws: a window bigger than the hole
 * shows the far side round the outside of the ring.
 */
public final class Portal implements PortalEnd {

    public enum Phase { OPENING, OPEN, CLOSING, CLOSED }

    /** How open a closed portal's spark is, as a share of the ring: the shader's DUST. */
    static final double DUST = 0.06;

    private final Frame frame;
    private ShaderObject object;
    private final double ringRadius;
    private Portal partner;
    private Phase phase = Phase.OPENING;
    private int phaseStart = Ticks.now();
    private long phaseGameTime;

    Portal(Frame frame, ShaderObject object, double ringRadius) {
        this.frame = frame;
        this.object = object;
        this.ringRadius = ringRadius;
    }

    void pair(Portal partner) {
        this.partner = partner;
    }

    @Override
    public Location middle() {
        return frame.middle();
    }

    @Override
    public BlockFace facing() {
        return switch (frame.facing()) {
            case 0 -> BlockFace.SOUTH;
            case 1 -> BlockFace.WEST;
            case 2 -> BlockFace.NORTH;
            default -> BlockFace.EAST;
        };
    }

    @Override
    public double hole() {
        return radius() * 2;
    }

    public Frame frame() {
        return frame;
    }

    public Portal partner() {
        return partner;
    }

    public ShaderObject object() {
        return object;
    }

    /** The ring, when the game has swapped the entity under it for a copy loaded from disk. */
    void object(ShaderObject object) {
        this.object = object;
    }

    /** The ring's shader state for this phase. */
    String stateName() {
        return switch (phase) {
            case OPENING -> "opening";
            case OPEN -> "open";
            case CLOSING -> "closing";
            case CLOSED -> "closed";
        };
    }

    public Phase phase() {
        return phase;
    }

    void phase(Phase phase) {
        this.phase = phase;
        this.phaseStart = Ticks.now();
        this.phaseGameTime = frame.world().getGameTime();
        if (object != null && object.isValid()) {
            object.state(stateName());
        }
    }

    /** The world's game time when the phase began: the clock the shaders animate by. */
    public long phaseGameTime() {
        return phaseGameTime;
    }

    /** The ring's radius when fully open, in blocks. */
    public double ringRadius() {
        return ringRadius;
    }

    /** Seconds since the phase began. */
    double phaseSeconds() {
        return (Ticks.now() - phaseStart) / 20.0;
    }

    /** The radius of the hole now, in blocks: the shader's spOpen times the ring. */
    public double radius() {
        double t = phaseSeconds();
        double open = switch (phase) {
            case OPENING -> DUST + (1 - DUST) * smoothstep(0.15, 1.4, t);
            case OPEN -> 1;
            case CLOSING -> 1 - (1 - DUST) * smoothstep(0, 1, t);
            case CLOSED -> 0;
        };
        return ringRadius * open;
    }

    void remove() {
        if (object != null && object.isValid()) {
            object.remove();
        }
    }

    private static double smoothstep(double e0, double e1, double x) {
        double t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }
}
