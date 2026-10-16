package ai.resourcepack.addons.portals.api;

import org.bukkit.Location;
import org.bukkit.block.BlockFace;

/** One end of a pair. */
public interface PortalEnd {

    /** The middle of the ring, looking out of its front. */
    Location middle();

    /** The way its front faces. */
    BlockFace facing();

    /** How wide the hole is now, in blocks across: 0 when shut to a ball. */
    double hole();
}
