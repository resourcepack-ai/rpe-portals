package ai.resourcepack.addons.portals.api;

import java.util.UUID;

/**
 * How a pair behaves. Start from {@link #builder()}; anything not set takes
 * the server's config.
 *
 * @param owner     who it belongs to: their pairs-per-player limit, and who may take it away
 * @param size      the ring's diameter in blocks, 2 to 12; 0 for the config's
 * @param sounds    whether it plays its sounds
 * @param travel    whether walking into it carries you through
 * @param window    whether the far side is seen through it
 * @param lifetime  ticks before it takes itself away; 0 keeps it, -1 for the config's
 * @param startShut whether it begins as a small glowing ball, to be opened later
 */
public record PortalOptions(UUID owner, double size, boolean sounds, boolean travel, boolean window,
                            int lifetime, boolean startShut) {

    /** Nobody: a pair a plugin opened for itself. */
    public static final UUID NO_OWNER = new UUID(0, 0);

    /** The smallest and largest a ring can be, in blocks across. */
    public static final double MIN_SIZE = 2;
    public static final double MAX_SIZE = 12;

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID owner = NO_OWNER;
        private double size;
        private boolean sounds = true;
        private boolean travel = true;
        private boolean window = true;
        private int lifetime = -1;
        private boolean startShut;

        public Builder owner(UUID owner) {
            this.owner = owner == null ? NO_OWNER : owner;
            return this;
        }

        /** The ring's diameter in blocks, {@link #MIN_SIZE} to {@link #MAX_SIZE}. */
        public Builder size(double blocks) {
            this.size = blocks;
            return this;
        }

        public Builder sounds(boolean on) {
            this.sounds = on;
            return this;
        }

        public Builder travel(boolean on) {
            this.travel = on;
            return this;
        }

        public Builder window(boolean on) {
            this.window = on;
            return this;
        }

        public Builder lifetimeTicks(int ticks) {
            this.lifetime = ticks;
            return this;
        }

        public Builder lifetimeSeconds(double seconds) {
            this.lifetime = (int) Math.round(seconds * 20);
            return this;
        }

        public Builder startShut(boolean shut) {
            this.startShut = shut;
            return this;
        }

        public PortalOptions build() {
            return new PortalOptions(owner, size, sounds, travel, window, lifetime, startShut);
        }
    }
}
