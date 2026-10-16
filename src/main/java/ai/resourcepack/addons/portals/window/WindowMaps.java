package ai.resourcepack.addons.portals.window;

import ai.resourcepack.addons.portals.portal.Frame;
import ai.resourcepack.addons.portals.portal.Portal;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every portal's window: the place on the other side, written into a stack of
 * maps that hang in invisible item frames at the ring, for the pack's text
 * shader to draw.
 *
 * <p>The same maps go to every player, because nothing in them depends on
 * where anybody is standing: they hold the far side's blocks in the portal's
 * own frame, and the shader works out what each eye sees. So nothing is sent as
 * anybody moves, and nothing in the world behind the portal is touched.
 *
 * <p>A map is self-contained, because the shader drawing it can read nothing
 * else. It holds two layers of the far side ({@link #WIDTH} across and
 * {@link #DEPTH} deep, side by side in its top half), a header row, and in its
 * bottom half up to {@link #TILES} sixteen-square textures, the real ones of
 * the blocks in those layers. A cell names one of up to {@link #MATERIALS}
 * materials the header lists (a shape and its top, side and bottom textures)
 * and the light it stands in.
 */
public final class WindowMaps {

    public static final int WIDTH = 128;
    public static final int DEPTH = 63;
    /**
     * One layer a map, the whole width of it: looking through a portal at an
     * angle carries the eye sideways fast, and 64 across ran out at the sides
     * of the view while there was depth to spare.
     */
    public static final int LAYERS_PER_MAP = 1;
    public static final int MAPS = 28;
    /** Where the window's lowest layer is, from the block the ring's middle is in. */
    public static final int BASE = -8;
    public static final int TILES = 27;
    /**
     * The last five texture slots hold, one bit a cell, which cells of the layer
     * under this map's two and the layer over them hold a full block: with the
     * two layers the map draws, that is every neighbour the game's ambient
     * occlusion looks at.
     */
    private static final int OCC_X = 48;
    private static final int OCC_Y = 112;
    private static final int OCC_W = 80;
    /** The cells the occlusion bits cover: 80 across the middle, 40 deep. */
    private static final int OCC_FROM = (WIDTH - OCC_W) / 2;
    private static final int OCC_DEPTH = 40;
    /** Where the header says how many materials, from the first, are full blocks. */
    public static final int OCCLUDERS = 120;
    public static final int MATERIALS = 19;
    /**
     * Where the far side's block and facing sit in the header, after the
     * material table: the shader needs real world coordinates to nudge each
     * plant as the game does, and the far side's compass to shade each face.
     */
    public static final int FAR = 111;
    /** Where the material table starts in the header row. */
    public static final int TABLE = 16;
    /**
     * Where the far side's dimension and fog colour sit in the header, after the
     * material table: the Nether is drawn in its own red fog and its own light,
     * not the sky and daylight of whoever is looking.
     */
    public static final int ENV = 122;
    /**
     * The horizon: one more map of the land beyond, coarse. Each of its cells
     * is a column {@link #H_CELL} blocks square; {@link #H_ACROSS} across and
     * {@link #H_DEPTH} deep, a height in the left half and the colour of the
     * top block in the right.
     */
    public static final int H_CELL = 3;
    public static final int H_ACROSS = 64;
    public static final int H_DEPTH = 127;
    /** A horizon height is stored as this much above its symbol. */
    public static final int H_BIAS = 60;
    /**
     * Two more maps after the horizon: the CANOPY (treetops beyond the detailed
     * part, as slabs floating over the ground the horizon map holds, so a far
     * forest is trees rather than green pillars) and the CLOUDS (the game's own
     * cloud texture, seven cells to a pixel, so the window shows the very clouds
     * the far side has, where it has them).
     */
    public static final int CANOPY = MAPS + 1;
    public static final int CLOUDS = MAPS + 2;
    /** Where the cloud map's own header fields start: the far side's place and the clouds' clock. */
    public static final int CLOUD_FIELDS = 16;
    /** The game repeats its clouds every 256 cells, and moves them a cell in 400 ticks. */
    private static final int CLOUD_PERIOD_TICKS = 256 * 400;
    /** How often the horizon is read again, in ticks. */
    private static final int H_REFRESH = 20 * 60 * 5;

    private final Plugin plugin;
    private final MapColours colours;
    private final BlockLooks looks;
    private final File poolFile;
    private final Deque<Integer> pool = new ArrayDeque<>();
    private final List<Integer> everMade = new ArrayList<>();
    private final Map<Portal, Window> windows = new HashMap<>();

    public WindowMaps(Plugin plugin, MapColours colours, BlockLooks looks) {
        this.plugin = plugin;
        this.colours = colours;
        this.looks = looks;
        this.poolFile = new File(plugin.getDataFolder(), "maps.yml");
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(poolFile);
        for (int id : saved.getIntegerList("maps")) {
            pool.add(id);
            everMade.add(id);
        }
    }

    /** One map's pixels, drawn onto its canvas whenever they change. */
    private static final class Layer extends MapRenderer {
        final byte[] pixels = new byte[128 * 128];
        int version;
        int drawn = -1;

        Layer() {
            super(false);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void render(MapView map, MapCanvas canvas, Player player) {
            if (drawn == version) return;
            for (int y = 0; y < 128; y++) {
                for (int x = 0; x < 128; x++) {
                    canvas.setPixel(x, y, pixels[y * 128 + x]);
                }
            }
            drawn = version;
        }
    }

    private final class Window {
        final Portal portal;
        final List<MapView> maps = new ArrayList<>();
        final List<Layer> layers = new ArrayList<>();
        final String tag = "portals_" + UUID.randomUUID().toString().substring(0, 8);
        int next;
        Portal.Phase headerPhase;
        double temperature;
        double humidity;
        MapView horizonMap;
        final Layer horizon = new Layer();
        final byte[] heights = new byte[H_ACROSS * H_DEPTH];
        final byte[] tops = new byte[H_ACROSS * H_DEPTH];
        int horizonAt = -H_REFRESH;
        boolean horizonBusy;
        MapView canopyMap;
        final Layer canopy = new Layer();
        final byte[] canopyHeights = new byte[H_ACROSS * H_DEPTH];
        final byte[] canopyTops = new byte[H_ACROSS * H_DEPTH];
        MapView cloudMap;
        final Layer clouds = new Layer();
        int cloudTicks = -1;
        /** 0 overworld, 1 Nether, 2 End: the far side's, and this side's. */
        int farEnv;
        int nearEnv;
        /** The far side's fog, as the game colours it there. */
        int fog;

        Window(Portal portal) {
            this.portal = portal;
        }
    }

    /** Builds a portal's window: reads the far side whole, and hangs the maps. */
    public void open(Portal portal) {
        if (windows.containsKey(portal) || portal.partner() == null) return;
        Window w = new Window(portal);
        Frame there = portal.partner().frame();
        World world = portal.frame().world();
        int px = (int) Math.floor(there.worldX(0, 8));
        int py = (int) Math.floor(there.worldY(0));
        int pz = (int) Math.floor(there.worldZ(0, 8));
        w.temperature = there.world().getTemperature(px, py, pz);
        w.humidity = there.world().getHumidity(px, py, pz);
        w.farEnv = env(there.world());
        w.nearEnv = env(world);
        w.fog = fogOf(there.world(), px, py, pz);
        for (int k = 0; k < MAPS; k++) {
            MapView map = take(world);
            Layer layer = new Layer();
            // getRenderers() is a copy: the game's own renderer has to be removed one by one,
            // or it paints the empty world map over these pixels every time it draws.
            for (MapRenderer r : map.getRenderers()) map.removeRenderer(r);
            map.addRenderer(layer);
            map.setTrackingPosition(false);
            map.setUnlimitedTracking(false);
            w.maps.add(map);
            w.layers.add(layer);
        }
        w.horizonMap = take(world);
        for (MapRenderer r : w.horizonMap.getRenderers()) w.horizonMap.removeRenderer(r);
        w.horizonMap.addRenderer(w.horizon);
        w.horizonMap.setTrackingPosition(false);
        w.canopyMap = take(world);
        for (MapRenderer r : w.canopyMap.getRenderers()) w.canopyMap.removeRenderer(r);
        w.canopyMap.addRenderer(w.canopy);
        w.canopyMap.setTrackingPosition(false);
        w.cloudMap = take(world);
        for (MapRenderer r : w.cloudMap.getRenderers()) w.cloudMap.removeRenderer(r);
        w.cloudMap.addRenderer(w.clouds);
        w.cloudMap.setTrackingPosition(false);
        java.util.Arrays.fill(w.heights, colours.symbol(199));
        java.util.Arrays.fill(w.canopyHeights, colours.symbol(199));
        windows.put(portal, w);
        for (int k = 0; k < MAPS; k++) sample(w, k);
        writeHorizon(w);
        writeClouds(w);
        w.headerPhase = portal.phase();
        hang(w);
    }

    public void close(Portal portal) {
        Window w = windows.remove(portal);
        if (w == null) return;
        for (Entity e : portal.frame().world().getEntities()) {
            if (e.getScoreboardTags().contains(w.tag)) e.remove();
        }
        for (MapView map : allMaps(w)) {
            for (MapRenderer r : map.getRenderers()) map.removeRenderer(r);
            pool.add(map.getId());
        }
    }

    public void closeAll() {
        for (Portal p : new ArrayList<>(windows.keySet())) close(p);
        save();
    }

    /** Every window drawn again from scratch: for when the textures arrive. */
    public void redrawAll() {
        cloudBits = null;
        for (Window w : windows.values()) {
            for (int k = 0; k < MAPS; k++) sample(w, k);
            w.cloudTicks = -1;
            writeClouds(w);
            // And the far land again, in its real colours.
            w.horizonAt = -H_REFRESH;
        }
    }

    private static List<MapView> allMaps(Window w) {
        List<MapView> all = new ArrayList<>(w.maps);
        all.add(w.horizonMap);
        all.add(w.canopyMap);
        all.add(w.cloudMap);
        return all;
    }

    /** Once a tick: one map of every window read again, and every header kept up with its ring. */
    public void tick() {
        for (Window w : windows.values()) {
            if (w.headerPhase != w.portal.phase()) {
                for (int k = 0; k < MAPS; k++) {
                    Layer l = w.layers.get(k);
                    if (writeHeader(w, l.pixels, k, null)) l.version++;
                }
                if (writeHeader(w, w.horizon.pixels, MAPS, null)) w.horizon.version++;
                if (writeHeader(w, w.canopy.pixels, CANOPY, null)) w.canopy.version++;
                if (writeHeader(w, w.clouds.pixels, CLOUDS, null)) w.clouds.version++;
                w.headerPhase = w.portal.phase();
            }
            // The clouds' clock is re-based each time the viewer's own clock wraps.
            if (Bukkit.getCurrentTick() % 20 == 0) writeClouds(w);
            if (!w.horizonBusy && Bukkit.getCurrentTick() - w.horizonAt >= H_REFRESH) readHorizon(w);
            sample(w, w.next);
            w.next = (w.next + 1) % MAPS;
        }
    }

    private MapView take(World world) {
        while (!pool.isEmpty()) {
            MapView map = Bukkit.getMap(pool.poll());
            if (map != null) return map;
        }
        MapView map = Bukkit.createMap(world);
        everMade.add(map.getId());
        save();
        return map;
    }

    private void save() {
        YamlConfiguration out = new YamlConfiguration();
        out.set("maps", everMade);
        try {
            out.save(poolFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save maps.yml: " + e.getMessage());
        }
    }

    /** A material as the header lists it. */
    private record Material(int shape, int top, int side, int bottom, int flags) {
    }

    /** Reads map {@code k}'s two layers from in front of the partner, with their textures. */
    private void sample(Window w, int k) {
        Frame here = w.portal.frame();
        Frame there = w.portal.partner().frame();
        World world = there.world();
        int floorY = (int) Math.floor(there.worldY(0));

        // First pass: what is in every cell, and how much of each look there is.
        BlockLooks.Look[] cellLook = new BlockLooks.Look[LAYERS_PER_MAP * WIDTH * DEPTH];
        int[] cellLight = new int[cellLook.length];
        Map<BlockLooks.Look, Integer> counts = new java.util.IdentityHashMap<>();
        for (int q = 0; q < LAYERS_PER_MAP; q++) {
            int y = floorY + BASE + k * LAYERS_PER_MAP + q;
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) continue;
            for (int iz = 0; iz < DEPTH; iz++) {
                double c = iz + 0.5;
                for (int ix = 0; ix < WIDTH; ix++) {
                    int ia = ix - WIDTH / 2;
                    int x = (int) Math.floor(there.worldX(-ia, c));
                    int z = (int) Math.floor(there.worldZ(-ia, c));
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                    BlockData data = world.getBlockData(x, y, z);
                    if (BlockLooks.empty(data)) continue;
                    BlockLooks.Look look = looks.of(data, looks.tint(world, x, y, z));
                    int i = (q * DEPTH + iz) * WIDTH + ix;
                    if (look.shape() == BlockLooks.LIQUID) {
                        // Only the surface is drawn, and it is drawn seeing through to the
                        // bottom: the cell carries how deep the water is instead of a light.
                        if (y + 1 < world.getMaxHeight() && world.getBlockData(x, y + 1, z).getMaterial() == data.getMaterial()) {
                            continue;
                        }
                        int depth = 0;
                        BlockLooks.Look bottom = null;
                        for (int dy = 1; dy <= 8 && y - dy >= world.getMinHeight(); dy++) {
                            BlockData under = world.getBlockData(x, y - dy, z);
                            if (BlockLooks.empty(under)) break;
                            BlockLooks.Look u = looks.of(under, looks.tint(world, x, y - dy, z));
                            // Seagrass and kelp stand in the water: the floor is under them.
                            if (u.shape() == BlockLooks.CROSS || u.shape() == BlockLooks.VINE) {
                                depth++;
                                continue;
                            }
                            if (u.shape() != BlockLooks.LIQUID) {
                                bottom = u;
                                break;
                            }
                            depth++;
                        }
                        look = looks.liquidOver(look, bottom);
                        cellLight[i] = Math.min(15, depth * 2);
                    } else {
                        // The light a face is lit by is the light of the air in front of it:
                        // the cell above it, or the one between it and the portal, or failing
                        // those whichever side it shows, so a face lit from one side is not black.
                        int l = Math.max(light(world, x, y + 1, z), light(world,
                                (int) Math.floor(there.worldX(-ia, c - 1)), y, (int) Math.floor(there.worldZ(-ia, c - 1))));
                        if (l < 15) {
                            l = Math.max(l, Math.max(light(world, x + 1, y, z), light(world, x - 1, y, z)));
                            l = Math.max(l, Math.max(light(world, x, y, z + 1), light(world, x, y, z - 1)));
                            l = Math.max(l, light(world, x, y - 1, z));
                        }
                        cellLight[i] = l;
                    }
                    cellLook[i] = look;
                    counts.merge(look, 1, Integer::sum);
                }
            }
        }

        // The materials this map can name, most used first, and the textures they need.
        List<BlockLooks.Look> order = new ArrayList<>(counts.keySet());
        // Full blocks first, so the shader tells a neighbour that darkens a corner
        // by its material's number alone; most used first within each.
        order.sort((a, b) -> occludes(a) != occludes(b) ? (occludes(a) ? -1 : 1) : counts.get(b) - counts.get(a));
        Map<String, Integer> tileIndex = new HashMap<>();
        List<int[]> tiles = new ArrayList<>();
        Map<BlockLooks.Look, Integer> materialOf = new java.util.IdentityHashMap<>();
        List<Material> materials = new ArrayList<>();
        for (BlockLooks.Look look : order) {
            if (materials.size() >= MATERIALS) break;
            int top = tile(look.top(), tileIndex, tiles);
            int side = tile(look.side(), tileIndex, tiles);
            int bottom = tile(look.bottom(), tileIndex, tiles);
            if (side < 0) side = top;
            if (top < 0) top = side;
            if (bottom < 0) bottom = top;
            if (top < 0) continue;
            materialOf.put(look, materials.size());
            materials.add(new Material(look.shape(), top, side, bottom, flags(look, here, there)));
        }

        // Second pass: the cells. A look with no room of its own borrows the
        // material whose colour is nearest, so nothing goes missing.
        Layer layer = w.layers.get(k);
        byte[] fresh = new byte[128 * 128];
        for (int q = 0; q < LAYERS_PER_MAP; q++) {
            for (int iz = 0; iz < DEPTH; iz++) {
                for (int ix = 0; ix < WIDTH; ix++) {
                    int i = (q * DEPTH + iz) * WIDTH + ix;
                    BlockLooks.Look look = cellLook[i];
                    if (look == null) continue;
                    Integer m = materialOf.get(look);
                    if (m == null) m = nearest(look, materialOf);
                    if (m == null) continue;
                    fresh[iz * 128 + q * WIDTH + ix] = colours.symbol(m * 8 + (cellLight[i] >> 1));
                }
            }
        }
        for (int t = 0; t < tiles.size(); t++) {
            colours.encodeTile(tiles.get(t), fresh, (t % 8) * 16, 64 + (t / 8) * 16);
        }
        // Which cells of the layer under and the layer over this map's two are full blocks.
        int[] occ = new int[OCC_W * 16];
        for (int side = 0; side < 2; side++) {
            int y = floorY + BASE + k * LAYERS_PER_MAP + (side == 0 ? -1 : LAYERS_PER_MAP);
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) continue;
            // Only as far as ambient occlusion is drawn: the middle of the width, 40 deep.
            for (int iz = 0; iz < OCC_DEPTH; iz++) {
                double c = iz + 0.5;
                for (int ix = OCC_FROM; ix < OCC_FROM + OCC_W; ix++) {
                    int ia = ix - WIDTH / 2;
                    int x = (int) Math.floor(there.worldX(-ia, c));
                    int z = (int) Math.floor(there.worldZ(-ia, c));
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                    BlockData data = world.getBlockData(x, y, z);
                    if (BlockLooks.empty(data)) continue;
                    if (!occludes(looks.of(data, looks.tint(world, x, y, z)))) continue;
                    int idx = side * OCC_W * OCC_DEPTH + iz * OCC_W + (ix - OCC_FROM);
                    occ[idx / 7] |= 1 << (idx % 7);
                }
            }
        }
        for (int p = 0; p < occ.length; p++) {
            fresh[(OCC_Y + p / OCC_W) * 128 + OCC_X + p % OCC_W] = colours.symbol(occ[p]);
        }
        writeHeader(w, fresh, k, materials);
        if (!Arrays.equals(fresh, layer.pixels)) {
            System.arraycopy(fresh, 0, layer.pixels, 0, fresh.length);
            layer.version++;
        }
    }

    /** A full block, as the game's ambient occlusion counts one. */
    private static boolean occludes(BlockLooks.Look look) {
        return look.shape() == BlockLooks.CUBE || look.shape() == BlockLooks.CUTOUT;
    }

    private static int light(World world, int x, int y, int z) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) return 15;
        Block b = world.getBlockAt(x, y, z);
        return Math.max(b.getLightFromSky(), b.getLightFromBlocks());
    }

    private static int tile(int[] texels, Map<String, Integer> index, List<int[]> tiles) {
        String key = Arrays.toString(texels);
        Integer known = index.get(key);
        if (known != null) return known;
        if (tiles.size() >= TILES) return -1;
        tiles.add(texels);
        index.put(key, tiles.size() - 1);
        return tiles.size() - 1;
    }

    private static Integer nearest(BlockLooks.Look look, Map<BlockLooks.Look, Integer> materialOf) {
        Integer best = null;
        int bestD = Integer.MAX_VALUE;
        for (Map.Entry<BlockLooks.Look, Integer> e : materialOf.entrySet()) {
            int a = look.colour();
            int b = e.getKey().colour();
            int dr = (a >> 16 & 255) - (b >> 16 & 255);
            int dg = (a >> 8 & 255) - (b >> 8 & 255);
            int db = (a & 255) - (b & 255);
            int d = dr * dr + dg * dg + db * db + (look.shape() == e.getKey().shape() ? 0 : 20000);
            if (d < bestD) {
                bestD = d;
                best = e.getValue();
            }
        }
        return best;
    }

    /**
     * A material's orientation in the window's own grid (x across, z away from
     * the viewer): which axis a log runs along, and which walls a vine is on.
     * The far side is turned to face this portal, so its directions turn too.
     */
    private static int flags(BlockLooks.Look look, Frame here, Frame there) {
        int axis = 0;
        if (look.axis() != 'y') {
            Vector v = look.axis() == 'x' ? new Vector(1, 0, 0) : new Vector(0, 0, 1);
            int[] g = grid(there.carryDirection(here, v), here);
            axis = g[0] != 0 ? 1 : 2;
        }
        int faces = 0;
        if (look.shape() == BlockLooks.CROSS) faces = look.faces();
        if (look.shape() == BlockLooks.VINE) {
            Vector[] dirs = {new Vector(0, 0, -1), new Vector(1, 0, 0), new Vector(0, 0, 1), new Vector(-1, 0, 0)};
            for (int i = 0; i < 4; i++) {
                if ((look.faces() & (1 << i)) == 0) continue;
                int[] g = grid(there.carryDirection(here, dirs[i]), here);
                if (g[0] > 0) faces |= 1;
                if (g[0] < 0) faces |= 2;
                if (g[1] > 0) faces |= 4;
                if (g[1] < 0) faces |= 8;
            }
        }
        return axis * 16 + faces;
    }

    /** A world direction at this portal in grid terms: across, and away into the window. */
    private static int[] grid(Vector world, Frame here) {
        Vector origin = here.middle().toVector();
        double a = here.a(origin.getX() + world.getX(), origin.getZ() + world.getZ());
        double c = here.c(origin.getX() + world.getX(), origin.getZ() + world.getZ());
        return new int[]{(int) Math.round(a), (int) Math.round(-c)};
    }

    private static int env(World world) {
        return switch (world.getEnvironment()) {
            case NETHER -> 1;
            case THE_END -> 2;
            default -> 0;
        };
    }

    /**
     * The colour the window paints its sky with: under a roof each Nether biome's
     * fog and the End's dark purple, and in the overworld the far biome's own sky
     * colour at noon, made from its temperature exactly as the game makes it (the
     * shader dims it with the viewer's daylight and fogs it as the game fogs its sky).
     */
    private static int fogOf(World world, int x, int y, int z) {
        String biome = world.getBiome(x, y, z).getKey().getKey();
        return switch (world.getEnvironment()) {
            case NETHER -> switch (biome) {
                case "crimson_forest" -> 0x330303;
                case "warped_forest" -> 0x1A051A;
                case "soul_sand_valley" -> 0x1B4745;
                case "basalt_deltas" -> 0x685F70;
                default -> 0x330808;
            };
            case THE_END -> 0x181318;
            default -> {
                float f = (float) Math.max(-1, Math.min(1, world.getTemperature(x, y, z) / 3.0));
                yield java.awt.Color.HSBtoRGB(0.62222224F - f * 0.05F, 0.5F + f * 0.1F, 1.0F) & 0xFFFFFF;
            }
        };
    }

    /** The header row: where the portal is and how open, then the material table. */
    private boolean writeHeader(Window w, byte[] px, int slab, List<Material> materials) {
        Frame f = w.portal.frame();
        int bx = (int) Math.floor(f.worldX(0, 0));
        int by = (int) Math.floor(f.worldY(0));
        int bz = (int) Math.floor(f.worldZ(0, 0));
        int phase = switch (w.portal.phase()) {
            case OPENING -> 0;
            case OPEN -> 1;
            case CLOSING -> 2;
            case CLOSED -> 3;
        };
        int start = (int) Math.floorMod(w.portal.phaseGameTime(), 24000L);
        int[] fields = {
                -1, -1, slab, MAPS,
                Math.floorMod(bx, 40000) / 200, Math.floorMod(bx, 40000) % 200,
                (by + 1000) / 200, (by + 1000) % 200,
                Math.floorMod(bz, 40000) / 200, Math.floorMod(bz, 40000) % 200,
                f.facing(),
                (int) Math.round(f.heightOffset() * 199),
                (int) Math.min(199, Math.round(w.portal.ringRadius() * 40)),
                phase,
                start / 200, start % 200,
        };
        boolean changed = false;
        int row = DEPTH * 128;
        for (int i = 0; i < fields.length; i++) {
            byte v = i == 0 ? colours.mark0() : i == 1 ? colours.mark1() : colours.symbol(fields[i]);
            if (px[row + i] != v) {
                px[row + i] = v;
                changed = true;
            }
        }
        Frame there = w.portal.partner().frame();
        long fbx = Math.floorMod((long) Math.floor(there.worldX(0, 0)), 1L << 28);
        long fbz = Math.floorMod((long) Math.floor(there.worldZ(0, 0)), 1L << 28);
        int[] far = {
                (int) (fbx / 8000000), (int) (fbx / 40000 % 200), (int) (fbx / 200 % 200), (int) (fbx % 200),
                (int) (fbz / 8000000), (int) (fbz / 40000 % 200), (int) (fbz / 200 % 200), (int) (fbz % 200),
                there.facing(),
        };
        for (int i = 0; i < far.length; i++) {
            byte v = colours.symbol(far[i]);
            if (px[row + FAR + i] != v) {
                px[row + FAR + i] = v;
                changed = true;
            }
        }
        int[] env = {w.farEnv, (w.fog >> 16 & 255) * 199 / 255, (w.fog >> 8 & 255) * 199 / 255, (w.fog & 255) * 199 / 255, w.nearEnv};
        for (int i = 0; i < env.length; i++) {
            byte v = colours.symbol(env[i]);
            if (px[row + ENV + i] != v) {
                px[row + ENV + i] = v;
                changed = true;
            }
        }
        if (materials != null) {
            int occluders = 0;
            while (occluders < materials.size()
                    && (materials.get(occluders).shape() == BlockLooks.CUBE || materials.get(occluders).shape() == BlockLooks.CUTOUT)) {
                occluders++;
            }
            px[row + OCCLUDERS] = colours.symbol(occluders);
            for (int m = 0; m < materials.size(); m++) {
                Material mat = materials.get(m);
                int at = row + TABLE + m * 5;
                px[at] = colours.symbol(mat.shape());
                px[at + 1] = colours.symbol(mat.top());
                px[at + 2] = colours.symbol(mat.side());
                px[at + 3] = colours.symbol(mat.bottom());
                px[at + 4] = colours.symbol(mat.flags());
            }
        }
        return changed;
    }

    /**
     * The frames: invisible and fixed, a stack of every map in each block the
     * hole covers, on its plane. Each frame's map is drawn where it hangs, so a
     * piece of the window on screen always has its own frame on screen too: the
     * game culls a frame whose block is out of view, and one stack in the middle
     * vanished whenever you looked at the hole's edge from close by.
     */
    private void hang(Window w) {
        Frame f = w.portal.frame();
        int[][] dirs = {{0, 1}, {-1, 0}, {0, -1}, {1, 0}};
        int nx = dirs[f.facing()][0];
        int nz = dirs[f.facing()][1];
        org.bukkit.block.BlockFace face = switch (f.facing()) {
            case 0 -> org.bukkit.block.BlockFace.SOUTH;
            case 1 -> org.bukkit.block.BlockFace.WEST;
            case 2 -> org.bukkit.block.BlockFace.NORTH;
            default -> org.bukkit.block.BlockFace.EAST;
        };
        double r = w.portal.ringRadius() + 0.05;
        int by = (int) Math.floor(f.worldY(0));
        double cy = f.worldY(0);
        List<MapView> all = allMaps(w);
        int span = (int) Math.ceil(r) + 1;
        for (int i = -span; i <= span; i++) {
            for (int j = -span - 1; j <= span; j++) {
                // The cell: across [i - 0.5, i + 0.5], up [by + j, by + j + 1].
                double da = Math.max(0, Math.abs(i) - 0.5);
                double lo = by + j - cy;
                double db = lo > 0 ? lo : (lo + 1 < 0 ? -(lo + 1) : 0);
                if (da * da + db * db >= r * r) continue;
                int fx = (int) Math.floor(f.worldX(i, 0) + 0.5 * nx);
                int fz = (int) Math.floor(f.worldZ(i, 0) + 0.5 * nz);
                org.bukkit.Location at = new org.bukkit.Location(f.world(), fx + 0.5, by + j + 0.5, fz + 0.5);
                for (MapView map : all) {
                    org.bukkit.inventory.ItemStack item = new org.bukkit.inventory.ItemStack(org.bukkit.Material.FILLED_MAP);
                    org.bukkit.inventory.meta.MapMeta meta = (org.bukkit.inventory.meta.MapMeta) item.getItemMeta();
                    meta.setMapView(map);
                    item.setItemMeta(meta);
                    f.world().spawn(at, org.bukkit.entity.ItemFrame.class, frame -> {
                        frame.setFacingDirection(face, true);
                        frame.setFixed(true);
                        frame.setVisible(false);
                        frame.setInvulnerable(true);
                        frame.setSilent(true);
                        frame.setPersistent(false);
                        frame.setItem(item, false);
                        frame.addScoreboardTag(w.tag);
                        frame.addScoreboardTag("portals_window");
                    });
                }
            }
        }
    }

    /**
     * Reads the land beyond the window, in the background: every chunk the
     * horizon covers is fetched without holding up the server, and each
     * column's height and top block taken from its snapshot.
     */
    private void readHorizon(Window w) {
        w.horizonBusy = true;
        w.horizonAt = Bukkit.getCurrentTick();
        Frame there = w.portal.partner().frame();
        World world = there.world();
        int floor = (int) Math.floor(there.worldY(0)) + BASE;
        // Under a roof, the highest block is the roof: the ground is found by
        // going down from the top of the detailed part instead.
        boolean roofed = world.getEnvironment() == World.Environment.NETHER;
        int roofStart = floor + MAPS * LAYERS_PER_MAP;
        Map<Long, List<int[]>> byChunk = new HashMap<>();
        for (int hz = 0; hz < H_DEPTH; hz++) {
            for (int hx = 0; hx < H_ACROSS; hx++) {
                double a = hx * H_CELL + H_CELL / 2.0 - H_ACROSS * H_CELL / 2.0;
                double c = hz * H_CELL + H_CELL / 2.0;
                int x = (int) Math.floor(there.worldX(-a, c));
                int z = (int) Math.floor(there.worldZ(-a, c));
                long key = ((long) (x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL);
                byChunk.computeIfAbsent(key, kk -> new ArrayList<>()).add(new int[]{hz * H_ACROSS + hx, x, z});
            }
        }
        java.util.concurrent.atomic.AtomicInteger left = new java.util.concurrent.atomic.AtomicInteger(byChunk.size());
        for (Map.Entry<Long, List<int[]>> e : byChunk.entrySet()) {
            int cx = (int) (e.getKey() >> 32);
            int cz = (int) (long) e.getKey();
            world.getChunkAtAsync(cx, cz, true).whenComplete((chunk, err) -> {
                if (chunk != null && windows.get(w.portal) == w) {
                    org.bukkit.ChunkSnapshot snap = chunk.getChunkSnapshot(true, false, false);
                    for (int[] col : e.getValue()) {
                        int lx = col[1] & 15;
                        int lz = col[2] & 15;
                        int top = Math.min(world.getMaxHeight() - 1, snap.getHighestBlockYAt(lx, lz));
                        if (roofed) {
                            top = Math.min(top, roofStart);
                            while (top > world.getMinHeight() && !BlockLooks.empty(snap.getBlockData(lx, top, lz))) top--;
                        }
                        BlockData data = snap.getBlockData(lx, top, lz);
                        // The highest block is reported as the first air above it on some versions.
                        while (top > world.getMinHeight() && BlockLooks.empty(data)) {
                            data = snap.getBlockData(lx, --top, lz);
                        }
                        if (BlockLooks.empty(data)) continue;
                        int[] tint = looks.tint(world, col[1], top, col[2]);
                        BlockLooks.Look look = looks.of(data, tint);
                        // Down through a tree to the ground under it, noting its top: the
                        // canopy is drawn floating, as a forest looks from far off, and a
                        // tuft of grass or a flower is the ground under it.
                        int canopyTop = Integer.MIN_VALUE;
                        int canopyColour = 0;
                        while (top > world.getMinHeight()) {
                            boolean leaves = look.shape() == BlockLooks.CUTOUT;
                            boolean through = leaves || look.shape() == BlockLooks.CROSS
                                    || look.shape() == BlockLooks.VINE || org.bukkit.Tag.LOGS.isTagged(data.getMaterial());
                            if (!through) break;
                            if (leaves && canopyTop == Integer.MIN_VALUE) {
                                canopyTop = top;
                                canopyColour = BlockLooks.average(look);
                            }
                            data = snap.getBlockData(lx, --top, lz);
                            while (top > world.getMinHeight() && BlockLooks.empty(data)) data = snap.getBlockData(lx, --top, lz);
                            look = looks.of(data, tint);
                        }
                        if (canopyTop != Integer.MIN_VALUE) {
                            w.canopyHeights[col[0]] = colours.symbol(Math.max(0, Math.min(198, canopyTop - floor + H_BIAS)));
                            w.canopyTops[col[0]] = colours.nearest(canopyColour, -1);
                        } else {
                            w.canopyHeights[col[0]] = colours.symbol(199);
                        }
                        // The nearest of every map colour: the shader knows water by its
                        // colour, and leaves have the canopy map to themselves.
                        byte colour = colours.nearest(BlockLooks.average(look), -1);
                        w.heights[col[0]] = colours.symbol(Math.max(0, Math.min(198, top - floor + H_BIAS)));
                        w.tops[col[0]] = colour;
                    }
                }
                if (left.decrementAndGet() == 0) {
                    w.horizonBusy = false;
                    if (windows.get(w.portal) == w) writeHorizon(w);
                }
            });
        }
    }

    /** The horizon map: heights left, colours right, the header row between. */
    private void writeHorizon(Window w) {
        byte[] px = new byte[128 * 128];
        for (int hz = 0; hz < H_DEPTH; hz++) {
            int row = hz < DEPTH ? hz : hz + 1;
            for (int hx = 0; hx < H_ACROSS; hx++) {
                px[row * 128 + hx] = w.heights[hz * H_ACROSS + hx];
                px[row * 128 + 64 + hx] = w.tops[hz * H_ACROSS + hx];
            }
        }
        writeHeader(w, px, MAPS, null);
        if (!Arrays.equals(px, w.horizon.pixels)) {
            System.arraycopy(px, 0, w.horizon.pixels, 0, px.length);
            w.horizon.version++;
        }
        byte[] cp = new byte[128 * 128];
        for (int hz = 0; hz < H_DEPTH; hz++) {
            int row = hz < DEPTH ? hz : hz + 1;
            for (int hx = 0; hx < H_ACROSS; hx++) {
                cp[row * 128 + hx] = w.canopyHeights[hz * H_ACROSS + hx];
                cp[row * 128 + 64 + hx] = w.canopyTops[hz * H_ACROSS + hx];
            }
        }
        writeHeader(w, cp, CANOPY, null);
        if (!Arrays.equals(cp, w.canopy.pixels)) {
            System.arraycopy(cp, 0, w.canopy.pixels, 0, cp.length);
            w.canopy.version++;
        }
    }

    /** The game's cloud texture as one bit a cell, 256 by 256, or null before the client's files arrive. */
    private boolean[] cloudBits;

    private boolean[] cloudBits() {
        if (cloudBits != null) return cloudBits;
        java.util.Optional<java.awt.image.BufferedImage> img = looks.assets().ready()
                ? looks.assets().image("textures/environment/clouds.png") : java.util.Optional.empty();
        if (img.isEmpty() || img.get().getWidth() != 256 || img.get().getHeight() != 256) return null;
        boolean[] bits = new boolean[256 * 256];
        for (int z = 0; z < 256; z++) {
            for (int x = 0; x < 256; x++) {
                // As the game reads it: a cell is cloud when its pixel is not (nearly) clear.
                bits[z * 256 + x] = (img.get().getRGB(x, z) >>> 24) >= 10;
            }
        }
        cloudBits = bits;
        return bits;
    }

    /**
     * The cloud map: the far side's clouds seven cells to a pixel, and in its
     * header where the far side is (the clouds are a pattern on the far world's
     * sky, so the shader needs that world's own coordinates) and the clouds'
     * clock relative to the viewer's, which the shader can only read modulo a day.
     */
    private void writeClouds(Window w) {
        Frame there = w.portal.partner().frame();
        World nearWorld = w.portal.frame().world();
        long near = nearWorld.getGameTime();
        long far = there.world().getGameTime();
        int ticks = (int) Math.floorMod(far - Math.floorMod(near, 24000L), (long) CLOUD_PERIOD_TICKS);
        boolean[] bits = w.farEnv == 0 ? cloudBits() : null;
        int ready = bits == null ? 0 : 1;
        int row = DEPTH * 128;
        if (ticks == w.cloudTicks && w.clouds.pixels[row + CLOUD_FIELDS] == colours.symbol(ready)) return;
        w.cloudTicks = ticks;
        byte[] px = new byte[128 * 128];
        if (bits != null) {
            for (int p = 0; p * 7 < bits.length; p++) {
                int v = 0;
                for (int b = 0; b < 7 && p * 7 + b < bits.length; b++) if (bits[p * 7 + b]) v |= 1 << b;
                int r = p / 128;
                if (r >= DEPTH) r++;
                px[r * 128 + p % 128] = colours.symbol(v);
            }
        }
        writeHeader(w, px, CLOUDS, null);
        int fx = Math.floorMod((int) Math.floor(there.worldX(0, 0)), 3072);
        int fz = Math.floorMod((int) Math.floor(there.worldZ(0, 0)), 3072);
        int fy = (int) Math.floor(there.worldY(0)) + BASE + 1000;
        int[] fields = {
                ready,
                fx / 200, fx % 200, fz / 200, fz % 200,
                there.facing(),
                fy / 200, fy % 200,
                ticks / 40000, ticks / 200 % 200, ticks % 200,
        };
        for (int i = 0; i < fields.length; i++) px[row + CLOUD_FIELDS + i] = colours.symbol(fields[i]);
        System.arraycopy(px, 0, w.clouds.pixels, 0, px.length);
        w.clouds.version++;
    }

    /** The portal whose window this entity is part of. */
    public java.util.Optional<Portal> portalOf(Entity entity) {
        for (Window w : windows.values()) {
            if (entity.getScoreboardTags().contains(w.tag)) return java.util.Optional.of(w.portal);
        }
        return java.util.Optional.empty();
    }

    /** For /portals debug: what every window holds. */
    public List<String> describe() {
        List<String> out = new ArrayList<>();
        for (Window w : windows.values()) {
            StringBuilder b = new StringBuilder("window at " + w.portal.frame().middle().toVector() + ":");
            for (int k = 0; k < w.maps.size(); k++) {
                b.append(" ").append(w.maps.get(k).getId()).append("v").append(w.layers.get(k).version);
            }
            out.add(b.toString());
        }
        return out;
    }


    /** Frames left over from a server that stopped without closing its portals. */
    public void sweep() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (e.getScoreboardTags().contains("portals_window")) e.remove();
            }
        }
    }
}
