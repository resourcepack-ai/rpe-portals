package ai.resourcepack.addons.portals.api;

import java.util.UUID;

/** Two portals that lead to each other. A handle stays valid until the pair is taken away. */
public interface PortalPair {

    UUID id();

    UUID owner();

    /** The end the pair was opened from. */
    PortalEnd here();

    /** The end it leads to. */
    PortalEnd there();

    /** Its ring's diameter in blocks. */
    double size();

    /** Open, or opening: a way through. */
    boolean isOpen();

    /** Still in the world. */
    boolean isValid();

    /** Spins it open from its ball. Does nothing when it is open. */
    void open();

    /** Pinches it down to a small glowing ball, where it stays. Does nothing when it is shut. */
    void shut();

    void toggle();

    /** Takes it away for good, shutting it first if it is open. */
    void remove();

    /** Whether walking into it carries you through. */
    boolean travel();

    void travel(boolean on);

    /** Whether it plays its sounds. */
    boolean sounds();

    void sounds(boolean on);
}
