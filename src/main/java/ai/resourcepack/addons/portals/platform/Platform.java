package ai.resourcepack.addons.portals.platform;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;

/**
 * The two things Paper does better than Spigot, done Paper's way where it is
 * there and the plain Bukkit way where it is not. The plugin compiles against
 * the Spigot API, so these are reached by name: one jar runs on both.
 *
 * <ul>
 *   <li><b>Walking through.</b> Paper teleports with relative velocity flags, so
 *   a player keeps their speed and has it turned with them in the same packet.
 *   On Spigot the player is teleported and then given that velocity.</li>
 *   <li><b>Reading the far land.</b> Paper loads a chunk in the background. On
 *   Spigot a chunk is loaded on the main thread, a few a tick, so reading a
 *   portal's horizon never stalls the server.</li>
 * </ul>
 */
public final class Platform {

    /** How many chunks Spigot loads a tick for the horizon. */
    private static final int CHUNKS_PER_TICK = 4;

    private static final Method TELEPORT_WITH_FLAGS;
    private static final Object VELOCITY_FLAGS;
    private static final Method CHUNK_AT_ASYNC;

    static {
        Method teleport = null;
        Object flags = null;
        try {
            Class<?> flag = Class.forName("io.papermc.paper.entity.TeleportFlag");
            Class<?> relative = Class.forName("io.papermc.paper.entity.TeleportFlag$Relative");
            Object[] wanted = {
                    enumValue(relative, "VELOCITY_X"), enumValue(relative, "VELOCITY_Y"),
                    enumValue(relative, "VELOCITY_Z"), enumValue(relative, "VELOCITY_ROTATION"),
            };
            Object array = Array.newInstance(flag, wanted.length);
            for (int i = 0; i < wanted.length; i++) Array.set(array, i, wanted[i]);
            teleport = Player.class.getMethod("teleport", Location.class, array.getClass());
            flags = array;
        } catch (ReflectiveOperationException | RuntimeException e) {
            teleport = null;
        }
        TELEPORT_WITH_FLAGS = teleport;
        VELOCITY_FLAGS = flags;
        Method async = null;
        try {
            async = World.class.getMethod("getChunkAtAsync", int.class, int.class, boolean.class);
        } catch (NoSuchMethodException e) {
            async = null;
        }
        CHUNK_AT_ASYNC = async;
    }

    private static final Deque<Load> loads = new ArrayDeque<>();

    private record Load(World world, int x, int z, CompletableFuture<Chunk> done) {
    }

    private Platform() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> type, String name) {
        return Enum.valueOf((Class) type, name);
    }

    /** Whether this is Paper (or a fork of it), for the log. */
    public static boolean isPaper() {
        return TELEPORT_WITH_FLAGS != null && CHUNK_AT_ASYNC != null;
    }

    /**
     * Puts a player at {@code to} moving at {@code velocity}, the velocity they
     * had already turned to match: in one packet on Paper, a teleport and then a
     * push on Spigot. A teleport to another world is always the second.
     */
    public static void carry(Player player, Location to, Vector velocity) {
        if (TELEPORT_WITH_FLAGS != null && to.getWorld() == player.getWorld()) {
            try {
                TELEPORT_WITH_FLAGS.invoke(player, to, VELOCITY_FLAGS);
                return;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Fall through to the plain way.
            }
        }
        player.teleport(to);
        player.setVelocity(velocity);
    }

    /** A chunk, generated if it must be: in the background on Paper, a few a tick on Spigot. Completes on the main thread. */
    @SuppressWarnings("unchecked")
    public static CompletableFuture<Chunk> chunkAt(World world, int x, int z) {
        if (CHUNK_AT_ASYNC != null) {
            try {
                return (CompletableFuture<Chunk>) CHUNK_AT_ASYNC.invoke(world, x, z, true);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Fall through to the plain way.
            }
        }
        CompletableFuture<Chunk> done = new CompletableFuture<>();
        loads.add(new Load(world, x, z, done));
        return done;
    }

    /** Once a tick, on the main thread: Spigot's share of the chunks waiting to load. */
    public static void tick() {
        for (int i = 0; i < CHUNKS_PER_TICK && !loads.isEmpty(); i++) {
            Load load = loads.poll();
            try {
                load.done().complete(load.world().getChunkAt(load.x(), load.z()));
            } catch (RuntimeException e) {
                load.done().completeExceptionally(e);
            }
        }
    }
}
