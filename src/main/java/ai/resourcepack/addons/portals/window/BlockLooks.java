package ai.resourcepack.addons.portals.window;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Slab;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a block looks like to the window: one of a few shapes, and the three
 * textures (top, side, bottom) it wears, already tinted for the biome and with
 * any overlay laid on, sixteen texels square.
 *
 * <p>Read from the game's own blockstates and models, so a block is drawn with
 * the faces the game gives it. The shapes are what a window can afford: a full
 * cube (see-through where its texture is, for leaves and glass), a plant's two
 * crossed planes, a vine's sheet on each wall it climbs, slabs, a thin layer,
 * and liquid. A stair or a fence is drawn as the cube nearest it.
 */
public final class BlockLooks {

    public static final int CUBE = 0;
    public static final int CUTOUT = 1;
    public static final int CROSS = 2;
    public static final int LIQUID = 3;
    public static final int SLAB_BOTTOM = 4;
    public static final int SLAB_TOP = 5;
    public static final int VINE = 6;
    public static final int THIN = 7;

    /** One block's look. Tiles are 256 ARGB texels, row by row from the top. */
    public record Look(int shape, int[] top, int[] side, int[] bottom, char axis, int faces, int colour) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Look l && l.shape == shape && l.axis == axis && l.faces == faces
                    && Arrays.equals(l.top, top) && Arrays.equals(l.side, side) && Arrays.equals(l.bottom, bottom);
        }

        @Override
        public int hashCode() {
            return ((shape * 31 + axis) * 31 + faces) * 31 + Arrays.hashCode(top) * 7 + Arrays.hashCode(side) * 3 + Arrays.hashCode(bottom);
        }
    }

    private final ClientAssets assets;
    private final Map<String, Optional<JsonObject>> models = new HashMap<>();
    private final Map<String, int[]> textures = new HashMap<>();
    private final Map<String, Look> looks = new HashMap<>();
    /** One instance per distinct look, so callers can count looks by identity. */
    private final Map<Look, Look> interned = new HashMap<>();
    private int[] grassMap;
    private int[] foliageMap;

    public BlockLooks(ClientAssets assets) {
        this.assets = assets;
    }

    /** Forgets everything read, for when the textures arrive. */
    public void reset() {
        models.clear();
        textures.clear();
        looks.clear();
        interned.clear();
        tints.clear();
        grassMap = null;
        foliageMap = null;
    }

    /**
     * How the game nudges a plant off the middle of its block: 0 not at all,
     * 1 sideways up to a quarter block, 2 sideways and down up to a fifth, 3
     * sideways up to an eighth. The amount comes from a hash of the block's
     * position, which the window's shader works out for itself; what only the
     * server knows is which blocks are nudged, so it is asked, block by block.
     */
    public static final int OFFSET_NONE = 0;
    public static final int OFFSET_XZ = 1;
    public static final int OFFSET_XYZ = 2;
    public static final int OFFSET_XZ_SMALL = 3;
    private static final Map<org.bukkit.Material, Integer> OFFSETS = new java.util.EnumMap<>(org.bukkit.Material.class);
    private static boolean offsetsChecked;

    static int offsetCode(BlockData data) {
        Integer known = OFFSETS.get(data.getMaterial());
        if (known != null) return known;
        int code = probeOffset(data);
        OFFSETS.put(data.getMaterial(), code);
        return code;
    }

    /** The game's own offset for a block, read through the server; -1 when it cannot be asked. */
    private static int probeOffset(BlockData data) {
        try {
            Object state = data.getClass().getMethod("getState").invoke(data);
            Class<?> posClass = Class.forName("net.minecraft.core.BlockPos");
            java.lang.reflect.Constructor<?> pos = posClass.getConstructor(int.class, int.class, int.class);
            java.lang.reflect.Method offset = null;
            for (java.lang.reflect.Method m : state.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("getOffset") && p.length >= 1 && p[p.length - 1] == posClass) offset = m;
            }
            if (offset == null) return fallbackOffset(data);
            boolean vertical = false;
            boolean any = false;
            double maxH = 0;
            double[][] seen = new double[48][];
            for (int i = 0; i < 48; i++) {
                int x = i * 37 - 811;
                int z = i * 53 + 129;
                Object at = pos.newInstance(x, 64, z);
                Object v = offset.getParameterCount() == 1 ? offset.invoke(state, at) : offset.invoke(state, null, at);
                double vx = v.getClass().getField("x").getDouble(v);
                double vy = v.getClass().getField("y").getDouble(v);
                double vz = v.getClass().getField("z").getDouble(v);
                seen[i] = new double[]{x, z, vx, vy, vz};
                if (vx != 0 || vy != 0 || vz != 0) any = true;
                if (vy != 0) vertical = true;
                maxH = Math.max(maxH, Math.max(Math.abs(vx), Math.abs(vz)));
            }
            if (!any) return OFFSET_NONE;
            int code = vertical ? OFFSET_XYZ : (maxH > 0.13 ? OFFSET_XZ : OFFSET_XZ_SMALL);
            if (!offsetsChecked) {
                offsetsChecked = true;
                int wrong = 0;
                for (double[] s : seen) {
                    double[] mine = vanillaOffset((int) s[0], (int) s[1], code);
                    if (Math.abs(mine[0] - s[2]) > 1e-3 || Math.abs(mine[1] - s[3]) > 1e-3 || Math.abs(mine[2] - s[4]) > 1e-3) wrong++;
                }
                if (wrong > 0) {
                    org.bukkit.Bukkit.getLogger().warning("[Portals] This server nudges plants differently from the window ("
                            + wrong + " of 48 checked); plants seen through a portal may sit a little off.");
                }
            }
            return code;
        } catch (Throwable t) {
            return fallbackOffset(data);
        }
    }

    private static int fallbackOffset(BlockData data) {
        String name = data.getMaterial().getKey().getKey();
        if (name.contains("grass") || name.endsWith("fern")) return OFFSET_XYZ;
        if (org.bukkit.Tag.FLOWERS.isTagged(data.getMaterial())) return OFFSET_XZ;
        return OFFSET_NONE;
    }

    /** The game's offset rule ({@code Mth.getSeed} at y 0), as the window's shader computes it. */
    static double[] vanillaOffset(int x, int z, int code) {
        long l = (long) (x * 3129871) ^ (long) z * 116129781L;
        l = l * l * 42317861L + l * 11L;
        l >>= 16;
        double maxH = code == OFFSET_XZ_SMALL ? 0.125 : 0.25;
        double ox = Math.max(-maxH, Math.min(maxH, ((l & 15L) / 15.0F - 0.5) * 0.5));
        double oy = code == OFFSET_XYZ ? ((l >> 4 & 15L) / 15.0F - 1.0) * 0.2 : 0;
        double oz = Math.max(-maxH, Math.min(maxH, ((l >> 8 & 15L) / 15.0F - 0.5) * 0.5));
        return new double[]{ox, oy, oz};
    }

    /** The game's own files, for the window's other readers (the clouds). */
    public ClientAssets assets() {
        return assets;
    }

    /** Is there nothing to draw here? */
    public static boolean empty(BlockData data) {
        Material m = data.getMaterial();
        return m.isAir() || m == Material.BARRIER || m == Material.LIGHT || m == Material.STRUCTURE_VOID;
    }

    /** The look of a block in a place whose climate is {@code temperature} and {@code humidity}. */
    public Look of(BlockData data, double temperature, double humidity) {
        return of(data, colormap(true, temperature, humidity), colormap(false, temperature, humidity));
    }

    /** A block tinted with its biome's grass and foliage colours, as {@link #tint} gives them. */
    public Look of(BlockData data, int[] tint) {
        return of(data, tint[0], tint[1]);
    }

    private Look of(BlockData data, int grass, int foliage) {
        String key = data.getAsString() + "|" + grass + "|" + foliage;
        Look known = looks.get(key);
        if (known != null) return known;
        Look look = resolve(data, grass, foliage);
        look = interned.computeIfAbsent(look, l -> l);
        looks.put(key, look);
        return look;
    }

    /** A look's top texture averaged to one colour, as the horizon draws it. */
    public static int average(Look look) {
        long r = 0, g = 0, b = 0, n = 0;
        for (int c : look.top()) {
            if ((c >>> 24) < 128) continue;
            r += c >> 16 & 255;
            g += c >> 8 & 255;
            b += c & 255;
            n++;
        }
        if (n == 0) return look.colour();
        return (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
    }

    /**
     * Water seen from above, over a bottom: the water's own texture on its
     * surface, and the bottom's top texture to be seen through it. The shader
     * reads the bottom from the {@code bottom} slot and how deep it is from the
     * cell.
     */
    public Look liquidOver(Look water, Look bottom) {
        int[] under = bottom == null ? water.top() : bottom.top();
        Look look = new Look(LIQUID, water.top(), water.side(), under, 'y', 0, water.colour());
        return interned.computeIfAbsent(look, l -> l);
    }

    // ---- resolving ------------------------------------------------------------

    private Look resolve(BlockData data, int grass, int foliage) {
        Material m = data.getMaterial();
        String name = m.getKey().getKey();
        int mapColour = data.getMapColor().asRGB();
        if (!assets.ready()) return flat(data, mapColour);

        if (m == Material.WATER || m == Material.BUBBLE_COLUMN) {
            int[] t = tint(texture("block/water_still"), 0x3F76E4);
            return new Look(LIQUID, t, t, t, 'y', 0, mapColour);
        }
        if (m == Material.LAVA) {
            int[] t = texture("block/lava_still");
            return new Look(LIQUID, t, t, t, 'y', 0, mapColour);
        }

        List<JsonObject> parts = modelsFor(name, data);
        if (parts.isEmpty()) return flat(data, mapColour);
        JsonObject model = parts.get(0);
        Map<String, String> vars = new HashMap<>();
        List<JsonObject> elements = new ArrayList<>();
        flatten(model, vars, elements);
        if (elements.isEmpty()) return flat(data, mapColour);
        int tintColour = tintFor(name, grass, foliage);

        if (data instanceof MultipleFacing mf && (name.contains("vine") || name.equals("glow_lichen") || name.equals("sculk_vein"))) {
            JsonObject face = firstFace(elements.get(0));
            int[] t = faceTexture(face, vars, tintColour);
            int bits = 0;
            if (mf.hasFace(BlockFace.NORTH)) bits |= 1;
            if (mf.hasFace(BlockFace.EAST)) bits |= 2;
            if (mf.hasFace(BlockFace.SOUTH)) bits |= 4;
            if (mf.hasFace(BlockFace.WEST)) bits |= 8;
            if (bits == 0) bits = 15;
            return new Look(VINE, t, t, t, 'y', bits, mapColour);
        }

        for (JsonObject el : elements) {
            JsonObject rot = el.getAsJsonObject("rotation");
            if (rot != null && rot.has("axis") && rot.get("axis").getAsString().equals("y")
                    && Math.abs(Math.abs(rot.get("angle").getAsFloat()) - 45) < 1) {
                int[] t = faceTexture(firstFace(el), vars, tintColour);
                return new Look(CROSS, t, t, t, 'y', offsetCode(data), mapColour);
            }
        }

        // The textures of the faces of every full-size box, laid over one another
        // in order, which is how an overlay (grass on a grass block's side) is drawn.
        int[] up = null;
        int[] down = null;
        int[] side = null;
        boolean full = false;
        float minY = 16;
        float maxY = 0;
        for (JsonObject el : elements) {
            float[] from = vec(el, "from");
            float[] to = vec(el, "to");
            minY = Math.min(minY, from[1]);
            maxY = Math.max(maxY, to[1]);
            boolean isFull = from[0] <= 0 && from[1] <= 0 && from[2] <= 0 && to[0] >= 16 && to[1] >= 16 && to[2] >= 16;
            boolean isLayer = from[0] <= 0 && from[2] <= 0 && to[0] >= 16 && to[2] >= 16;
            if (!isFull && !isLayer) continue;
            full |= isFull;
            JsonObject faces = el.getAsJsonObject("faces");
            if (faces == null) continue;
            up = over(up, faces, "up", vars, tintColour);
            down = over(down, faces, "down", vars, tintColour);
            for (String s : new String[]{"north", "south", "east", "west"}) {
                if (faces.has(s)) {
                    side = over(side, faces, s, vars, tintColour);
                    break;
                }
            }
        }
        if (up == null && side == null) {
            // Nothing full-width: a torch, a fence post, a flower pot. A plant's
            // planes are the nearest thing to it that a window can draw.
            int[] t = faceTexture(firstFace(elements.get(0)), vars, tintColour);
            boolean cube = m.isSolid() && m.isOccluding();
            return new Look(cube ? CUBE : CROSS, t, t, t, 'y', cube ? 0 : offsetCode(data), mapColour);
        }
        if (up == null) up = side;
        if (side == null) side = up;
        if (down == null) down = up;
        char axis = data instanceof Orientable o ? Character.toLowerCase(o.getAxis().name().charAt(0)) : 'y';

        if (data instanceof Slab slab) {
            int shape = switch (slab.getType()) {
                case BOTTOM -> SLAB_BOTTOM;
                case TOP -> SLAB_TOP;
                default -> CUBE;
            };
            return new Look(shape, up, side, down, axis, 0, mapColour);
        }
        if (!full && maxY - minY <= 3) {
            return new Look(THIN, up, side, down, axis, 0, mapColour);
        }
        boolean seeThrough = hasHoles(up) || hasHoles(side) || Tag.LEAVES.isTagged(m);
        return new Look(seeThrough ? CUTOUT : CUBE, up, side, down, axis, 0, mapColour);
    }

    /** A block with no textures to hand: its map colour on every face. */
    private Look flat(BlockData data, int rgb) {
        int[] t = new int[256];
        Arrays.fill(t, 0xFF000000 | rgb);
        Material m = data.getMaterial();
        int shape = m == Material.WATER || m == Material.LAVA ? LIQUID : Tag.LEAVES.isTagged(m) ? CUTOUT : !m.isSolid() ? CROSS : CUBE;
        return new Look(shape, t, t, t, 'y', 0, rgb);
    }

    private List<JsonObject> modelsFor(String name, BlockData data) {
        List<JsonObject> out = new ArrayList<>();
        Optional<JsonObject> states = assets.readJson("blockstates/" + name + ".json");
        if (states.isEmpty()) return out;
        Map<String, String> props = properties(data.getAsString());
        JsonObject s = states.get();
        if (s.has("variants")) {
            JsonObject variants = s.getAsJsonObject("variants");
            for (Map.Entry<String, JsonElement> e : variants.entrySet()) {
                if (!matches(e.getKey(), props)) continue;
                model(e.getValue()).ifPresent(out::add);
                break;
            }
        } else if (s.has("multipart")) {
            for (JsonElement part : s.getAsJsonArray("multipart")) {
                JsonObject p = part.getAsJsonObject();
                if (p.has("when") && !when(p.get("when").getAsJsonObject(), props)) continue;
                model(p.get("apply")).ifPresent(out::add);
            }
        }
        return out;
    }

    private Optional<JsonObject> model(JsonElement apply) {
        JsonObject a = apply.isJsonArray() ? apply.getAsJsonArray().get(0).getAsJsonObject() : apply.getAsJsonObject();
        return loadModel(a.get("model").getAsString());
    }

    private Optional<JsonObject> loadModel(String ref) {
        String path = ref.replace("minecraft:", "");
        return models.computeIfAbsent(path, p -> assets.readJson("models/" + p + ".json"));
    }

    /** A model's texture variables and elements, through its parents; the child's win. */
    private void flatten(JsonObject model, Map<String, String> vars, List<JsonObject> elements) {
        JsonObject m = model;
        for (int depth = 0; m != null && depth < 16; depth++) {
            if (m.has("textures")) {
                for (Map.Entry<String, JsonElement> e : m.getAsJsonObject("textures").entrySet()) {
                    // A texture is a string, or since 1.21.9 an object naming its sprite.
                    JsonElement t = e.getValue();
                    if (t.isJsonObject() && t.getAsJsonObject().has("sprite")) t = t.getAsJsonObject().get("sprite");
                    if (t.isJsonPrimitive()) vars.putIfAbsent(e.getKey(), t.getAsString());
                }
            }
            if (elements.isEmpty() && m.has("elements")) {
                for (JsonElement el : m.getAsJsonArray("elements")) elements.add(el.getAsJsonObject());
            }
            m = m.has("parent") ? loadModel(m.get("parent").getAsString()).orElse(null) : null;
        }
    }

    private static Map<String, String> properties(String state) {
        Map<String, String> props = new HashMap<>();
        int open = state.indexOf('[');
        if (open < 0) return props;
        for (String kv : state.substring(open + 1, state.length() - 1).split(",")) {
            int eq = kv.indexOf('=');
            if (eq > 0) props.put(kv.substring(0, eq), kv.substring(eq + 1));
        }
        return props;
    }

    private static boolean matches(String key, Map<String, String> props) {
        if (key.isEmpty()) return true;
        for (String kv : key.split(",")) {
            int eq = kv.indexOf('=');
            if (eq < 0) continue;
            if (!kv.substring(eq + 1).equals(props.get(kv.substring(0, eq)))) return false;
        }
        return true;
    }

    private static boolean when(JsonObject when, Map<String, String> props) {
        if (when.has("OR")) {
            for (JsonElement e : when.getAsJsonArray("OR")) if (when(e.getAsJsonObject(), props)) return true;
            return false;
        }
        if (when.has("AND")) {
            for (JsonElement e : when.getAsJsonArray("AND")) if (!when(e.getAsJsonObject(), props)) return false;
            return true;
        }
        for (Map.Entry<String, JsonElement> e : when.entrySet()) {
            String have = props.get(e.getKey());
            boolean any = false;
            for (String v : e.getValue().getAsString().split("\\|")) any |= v.equals(have);
            if (!any) return false;
        }
        return true;
    }

    private static float[] vec(JsonObject el, String key) {
        JsonArray a = el.getAsJsonArray(key);
        return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
    }

    private static JsonObject firstFace(JsonObject el) {
        JsonObject faces = el.getAsJsonObject("faces");
        if (faces == null) return new JsonObject();
        for (String s : new String[]{"north", "south", "east", "west", "up", "down"}) {
            if (faces.has(s)) return faces.getAsJsonObject(s);
        }
        return new JsonObject();
    }

    private int[] over(int[] under, JsonObject faces, String face, Map<String, String> vars, int tintColour) {
        if (!faces.has(face)) return under;
        int[] t = faceTexture(faces.getAsJsonObject(face), vars, tintColour);
        if (under == null) return t;
        int[] out = under.clone();
        for (int i = 0; i < 256; i++) {
            int a = t[i] >>> 24;
            if (a == 0) continue;
            int r = mix(out[i] >> 16 & 255, t[i] >> 16 & 255, a);
            int g = mix(out[i] >> 8 & 255, t[i] >> 8 & 255, a);
            int b = mix(out[i] & 255, t[i] & 255, a);
            out[i] = Math.max(out[i] >>> 24, a) << 24 | r << 16 | g << 8 | b;
        }
        return out;
    }

    private static int mix(int a, int b, int alpha) {
        return (a * (255 - alpha) + b * alpha) / 255;
    }

    private int[] faceTexture(JsonObject face, Map<String, String> vars, int tintColour) {
        String ref = face.has("texture") ? face.get("texture").getAsString() : "#particle";
        for (int i = 0; i < 8 && ref.startsWith("#"); i++) ref = vars.getOrDefault(ref.substring(1), "block/stone");
        int[] t = texture(ref.replace("minecraft:", ""));
        return face.has("tintindex") ? tint(t, tintColour) : t;
    }

    /** A texture's first sixteen-square frame, as ARGB. */
    private int[] texture(String path) {
        return textures.computeIfAbsent(path, p -> {
            int[] out = new int[256];
            Arrays.fill(out, 0xFFFF00FF);
            Optional<BufferedImage> img = assets.image("textures/" + p + ".png");
            if (img.isEmpty()) return out;
            BufferedImage im = img.get();
            int w = im.getWidth();
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    out[y * 16 + x] = im.getRGB(x * w / 16, y * w / 16);
                }
            }
            return out;
        });
    }

    private static int[] tint(int[] t, int rgb) {
        if (rgb == 0xFFFFFF) return t;
        int[] out = new int[256];
        int tr = rgb >> 16 & 255;
        int tg = rgb >> 8 & 255;
        int tb = rgb & 255;
        for (int i = 0; i < 256; i++) {
            int c = t[i];
            out[i] = (c & 0xFF000000) | ((c >> 16 & 255) * tr / 255) << 16 | ((c >> 8 & 255) * tg / 255) << 8 | (c & 255) * tb / 255;
        }
        return out;
    }

    private static boolean hasHoles(int[] t) {
        for (int c : t) if ((c >>> 24) < 128) return true;
        return false;
    }

    /** What colour the game tints a block's tinted faces with, as it does for these. */
    private static int tintFor(String name, int grass, int foliage) {
        if (name.equals("spruce_leaves")) return 0x619961;
        if (name.equals("birch_leaves")) return 0x80A755;
        if (name.equals("lily_pad")) return 0x208030;
        if (name.contains("leaves") || name.contains("vine")) return foliage;
        if (name.equals("grass_block") || name.contains("grass") || name.contains("fern") || name.equals("sugar_cane")) return grass;
        if (name.contains("stem")) return 0xE0C71C;
        return 0xFFFFFF;
    }

    /** The biome colour at a climate, read off the game's own colour map, as the game reads it. */
    private final Map<String, int[]> tints = new HashMap<>();

    /**
     * The grass and foliage colours of the biome at a block, as the game picks
     * them: the colour maps by the biome's climate, except where a biome names
     * its own (badlands, swamps, cherry groves, the pale garden) or darkens it (a
     * dark forest). One answer per biome, so a view across three biomes costs
     * three looks per tinted block, not one per column.
     */
    public int[] tint(org.bukkit.World world, int x, int y, int z) {
        org.bukkit.block.Biome biome = world.getBiome(x, y, z);
        String key = world.getName() + "|" + biome.getKey();
        int[] known = tints.get(key);
        if (known != null) return known;
        int grass = colormap(true, world.getTemperature(x, y, z), world.getHumidity(x, y, z));
        int foliage = colormap(false, world.getTemperature(x, y, z), world.getHumidity(x, y, z));
        switch (biome.getKey().getKey()) {
            case "badlands", "eroded_badlands", "wooded_badlands" -> {
                grass = 0x90814D;
                foliage = 0x9E814D;
            }
            case "swamp" -> {
                grass = 0x6A7039;
                foliage = 0x6A7039;
            }
            case "mangrove_swamp" -> {
                grass = 0x6A7039;
                foliage = 0x8DB127;
            }
            case "cherry_grove" -> {
                grass = 0xB6DB61;
                foliage = 0xB6DB61;
            }
            case "pale_garden" -> {
                grass = 0x778272;
                foliage = 0x878D76;
            }
            case "dark_forest" -> grass = ((grass & 0xFEFEFE) + 0x28340A) >> 1;
            default -> {
            }
        }
        int[] t = {grass, foliage};
        tints.put(key, t);
        return t;
    }

    private int colormap(boolean grass, double temperature, double humidity) {
        int[] map = grass ? grassMap : foliageMap;
        if (map == null) {
            map = new int[256 * 256];
            Arrays.fill(map, grass ? 0x91BD59 : 0x77AB2F);
            Optional<BufferedImage> img = assets.image("textures/colormap/" + (grass ? "grass" : "foliage") + ".png");
            if (img.isPresent() && img.get().getWidth() == 256) img.get().getRGB(0, 0, 256, 256, map, 0, 256);
            if (grass) grassMap = map;
            else foliageMap = map;
        }
        double t = Math.max(0, Math.min(1, temperature));
        double h = Math.max(0, Math.min(1, humidity)) * t;
        int x = (int) ((1 - t) * 255);
        int y = (int) ((1 - h) * 255);
        return map[y * 256 + x] & 0xFFFFFF;
    }
}
