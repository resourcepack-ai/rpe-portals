package ai.resourcepack.addons.portals.portal;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.util.Vector;

/**
 * Where a portal stands and which way it faces, and the arithmetic of going
 * from one portal to its partner.
 *
 * <p>A portal faces one of the four compass directions, and that is a choice
 * rather than a shortcut: the window is made of the far side's real blocks, and
 * a block turned by anything but a quarter turn is not a block. For the same
 * reason the middle sits on the grid in a particular way: on a block edge along
 * the way it faces, and in the middle of a block across it, so every block
 * behind one portal is exactly one block in front of the other.
 *
 * <p>Local coordinates are {@code a} across (to the portal's left as you face
 * it), {@code b} up and {@code c} out of its front. Going through turns you half
 * round: a point at (a, b, c) behind this portal is (-a, b, -c) at the partner,
 * which is in front of it, and your left is still your left.
 */
public final class Frame {

    /** Quarter turns of yaw: 0 faces south, 1 west, 2 north, 3 east, as an entity's yaw does. */
    private final int facing;
    private final World world;
    private final double cx;
    private final double cy;
    private final double cz;
    // the front (n) and across (s), both whole unit vectors along x or z
    private final int nx;
    private final int nz;
    private final int sx;
    private final int sz;

    private Frame(World world, double cx, double cy, double cz, int facing) {
        this.world = world;
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
        this.facing = Math.floorMod(facing, 4);
        int[][] dirs = {{0, 1}, {-1, 0}, {0, -1}, {1, 0}};
        this.nx = dirs[this.facing][0];
        this.nz = dirs[this.facing][1];
        int[] across = dirs[(this.facing + 1) % 4];
        this.sx = across[0];
        this.sz = across[1];
    }

    /**
     * A portal standing on the block at {@code ground} (the block a player
     * standing there is in), facing {@code yaw} snapped to a quarter turn,
     * with its middle {@code lift} blocks above the ground.
     */
    public static Frame standing(Location ground, float yaw, double lift) {
        int facing = Math.floorMod(Math.round(yaw / 90f), 4);
        int bx = ground.getBlockX();
        int by = ground.getBlockY();
        int bz = ground.getBlockZ();
        Frame probe = new Frame(ground.getWorld(), 0, 0, 0, facing);
        // On a block edge along the front, mid-block across it.
        double cx = bx + (probe.nx != 0 ? (probe.nx > 0 ? 1.0 : 0.0) : 0.5);
        double cz = bz + (probe.nz != 0 ? (probe.nz > 0 ? 1.0 : 0.0) : 0.5);
        return new Frame(ground.getWorld(), cx, by + lift, cz, facing);
    }

    public World world() {
        return world;
    }

    public int facing() {
        return facing;
    }

    /** The yaw the portal's front looks along. */
    public float yaw() {
        return facing * 90f;
    }

    public Location middle() {
        return new Location(world, cx, cy, cz, yaw(), 0f);
    }

    public double a(double x, double z) {
        return (x - cx) * sx + (z - cz) * sz;
    }

    public double b(double y) {
        return y - cy;
    }

    public double c(double x, double z) {
        return (x - cx) * nx + (z - cz) * nz;
    }

    public double worldX(double a, double c) {
        return cx + a * sx + c * nx;
    }

    public double worldY(double b) {
        return cy + b;
    }

    public double worldZ(double a, double c) {
        return cz + a * sz + c * nz;
    }

    /** How far the middle's height sits above a whole block, so two portals line up block for block. */
    public double heightOffset() {
        return cy - Math.floor(cy);
    }

    /** The degrees a thing going from this portal to {@code to} turns, clockwise from above as yaw counts. */
    public float turnTo(Frame to) {
        return Math.floorMod(to.facing + 2 - facing, 4) * 90f;
    }

    /** A point near this portal, where it is near {@code to}. */
    public Vector carry(Frame to, double x, double y, double z) {
        double a = a(x, z);
        double b = b(y);
        double c = c(x, z);
        return new Vector(to.worldX(-a, -c), to.worldY(b), to.worldZ(-a, -c));
    }

    /** A direction near this portal, turned as it is at {@code to}. */
    public Vector carryDirection(Frame to, Vector v) {
        double a = v.getX() * sx + v.getZ() * sz;
        double c = v.getX() * nx + v.getZ() * nz;
        return new Vector(-a * to.sx - c * to.nx, v.getY(), -a * to.sz - c * to.nz);
    }

    /**
     * How a block standing at {@code from} has to be turned to look right when
     * it is shown at this portal: the turn walking back the other way takes.
     */
    public StructureRotation blockTurnFrom(Frame from) {
        int quarters = Math.floorMod(Math.round(from.turnTo(this) / 90f), 4);
        return switch (quarters) {
            case 1 -> StructureRotation.CLOCKWISE_90;
            case 2 -> StructureRotation.CLOCKWISE_180;
            case 3 -> StructureRotation.COUNTERCLOCKWISE_90;
            default -> StructureRotation.NONE;
        };
    }
}
