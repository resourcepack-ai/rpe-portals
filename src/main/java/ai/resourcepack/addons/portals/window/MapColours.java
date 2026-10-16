package ai.resourcepack.addons.portals.window;

import org.bukkit.map.MapPalette;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntUnaryOperator;

/**
 * The client's map colours, and what the window says with them.
 *
 * <p>A map pixel is one of about 250 colours. The window uses 241 of them as
 * SYMBOLS 0..240, and two more as the marker that says a map is a window's:
 * the shader turns a pixel's RGB back into its symbol. Symbols 0..239 are a
 * texel (a luma level and a chroma digit, {@link #encodeTile}), 240 is a
 * see-through texel, and the header and the cells are small numbers.
 *
 * <p>The colours are read from the server's own copy of the game's table, the
 * same table the client uploads map textures with, so the shader decodes the
 * exact RGB it is given.
 */
public final class MapColours {

    /** Symbols 0..239 carry texels, 240 is a see-through texel. */
    public static final int SYMBOLS = 241;
    public static final int CLEAR = 240;
    public static final int LUMA = 30;

    private final int[] rgbById = new int[256];
    private final boolean[] opaque = new boolean[256];
    private final int[] symbolId = new int[SYMBOLS];
    private final int[] symbolOf = new int[256];
    private final int mark0;
    private final int mark1;
    /** A map colour base's own colour (full brightness) to its id, for the horizon. */
    private final Map<Integer, Integer> idOfBase = new HashMap<>();
    /** Sorted RGB to id, for the shader's binary search. */
    private final TreeMap<Integer, Integer> idByRgb = new TreeMap<>();

    public MapColours() {
        IntUnaryOperator colour = colourTable();
        Map<Integer, Integer> distinct = new LinkedHashMap<>();
        for (int id = 4; id < 256; id++) {
            int argb;
            try {
                argb = colour.applyAsInt(id);
            } catch (RuntimeException e) {
                continue;
            }
            if ((argb >>> 24) != 0xFF) continue;
            rgbById[id] = argb & 0xFFFFFF;
            opaque[id] = true;
            distinct.putIfAbsent(argb & 0xFFFFFF, id);
            if ((id & 3) == 2) idOfBase.putIfAbsent(argb & 0xFFFFFF, id);
        }
        idByRgb.putAll(invert(distinct));
        List<Integer> unique = new ArrayList<>(distinct.values());
        if (unique.size() < SYMBOLS + 2) {
            throw new IllegalStateException("Only " + unique.size() + " distinct map colours; the window needs " + (SYMBOLS + 2));
        }
        java.util.Arrays.fill(symbolOf, -1);
        for (int d = 0; d < SYMBOLS; d++) {
            symbolId[d] = unique.get(d);
            symbolOf[unique.get(d)] = d;
        }
        mark0 = unique.get(SYMBOLS);
        mark1 = unique.get(SYMBOLS + 1);
    }

    private static Map<Integer, Integer> invert(Map<Integer, Integer> rgbToId) {
        return new HashMap<>(rgbToId);
    }

    /**
     * {@code MapColor.getColorFromPackedId}, the table the client draws maps
     * with; Bukkit's own palette if the server's internals are not where they
     * were.
     */
    @SuppressWarnings("deprecation")
    private static IntUnaryOperator colourTable() {
        try {
            Class<?> mapColor = Class.forName("net.minecraft.world.level.material.MapColor");
            Method m = mapColor.getMethod("getColorFromPackedId", int.class);
            m.invoke(null, 4);
            return id -> {
                try {
                    return (int) m.invoke(null, id);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            };
        } catch (ReflectiveOperationException | RuntimeException e) {
            return id -> {
                java.awt.Color c = MapPalette.getColor((byte) id);
                return c.getAlpha() << 24 | (c.getRGB() & 0xFFFFFF);
            };
        }
    }

    /** The map colour that carries a symbol. */
    public byte symbol(int value) {
        return (byte) symbolId[Math.max(0, Math.min(SYMBOLS - 1, value))];
    }

    /** The map pixel that shows a block's own map colour, as {@code BlockData.getMapColor()} gives it. */
    public byte colourOf(int rgb) {
        Integer id = idOfBase.get(rgb & 0xFFFFFF);
        return id == null ? 0 : (byte) (int) id;
    }

    /** The map pixel nearest a colour, among all the distinct ones the client has. */
    public byte nearest(int rgb) {
        return nearest(rgb, -1);
    }

    /** What a horizon column is, carried in its colour's brightness (id & 3). */
    public static final int KIND_WATER = 0;
    public static final int KIND_LEAVES = 1;
    public static final int KIND_SOLID = 2;

    /** The nearest map colour of one brightness, or of any when {@code shade} is -1. */
    public byte nearest(int rgb, int shade) {
        int r = rgb >> 16 & 255;
        int g = rgb >> 8 & 255;
        int b = rgb & 255;
        int best = 0;
        long bestD = Long.MAX_VALUE;
        for (Map.Entry<Integer, Integer> e : idByRgb.entrySet()) {
            if (shade >= 0 && (e.getValue() & 3) != shade) continue;
            int c = e.getKey();
            int dr = r - (c >> 16 & 255);
            int dg = g - (c >> 8 & 255);
            int db = b - (c & 255);
            // Weighted by how much each channel shows at its level (the "redmean"
            // approximation of perceived difference): with blue weighed lightly, sand
            // came out grey.
            long rm = (r + (c >> 16 & 255)) / 2;
            long d = (512 + rm) * dr * dr / 256 + 4L * dg * dg + (767 - rm) * db * db / 256;
            if (d < bestD) {
                bestD = d;
                best = e.getValue();
            }
        }
        return (byte) best;
    }

    public byte mark0() {
        return (byte) mark0;
    }

    public byte mark1() {
        return (byte) mark1;
    }

    /**
     * A 16x16 ARGB texture as map pixels: YCbCr with the chroma shared by each
     * 2x2 block, so one symbol per texel is enough. A texel's symbol is its luma
     * (1..29; 0 is see-through) times 8 plus a digit, and the four digits of a
     * block spell its Cb and Cr at 64 levels each.
     */
    public void encodeTile(int[] argb, byte[] into, int x0, int y0) {
        for (int by = 0; by < 16; by += 2) {
            for (int bx = 0; bx < 16; bx += 2) {
                double cb = 0;
                double cr = 0;
                int n = 0;
                for (int k = 0; k < 4; k++) {
                    int c = argb[(by + (k >> 1)) * 16 + bx + (k & 1)];
                    if ((c >>> 24) < 128) continue;
                    int r = c >> 16 & 255;
                    int g = c >> 8 & 255;
                    int b = c & 255;
                    cb += 128 - 0.168736 * r - 0.331264 * g + 0.5 * b;
                    cr += 128 + 0.5 * r - 0.418688 * g - 0.081312 * b;
                    n++;
                }
                int qb = n == 0 ? 32 : (int) Math.max(0, Math.min(63, Math.round(cb / n * 63 / 255)));
                int qr = n == 0 ? 32 : (int) Math.max(0, Math.min(63, Math.round(cr / n * 63 / 255)));
                int[] digits = {qb >> 3, qb & 7, qr >> 3, qr & 7};
                for (int k = 0; k < 4; k++) {
                    int x = bx + (k & 1);
                    int y = by + (k >> 1);
                    int c = argb[y * 16 + x];
                    int sym;
                    if ((c >>> 24) < 128) {
                        // Luma 0 is see-through, and still carries its digit, so the
                        // block's chroma survives a texel nobody sees.
                        sym = digits[k];
                    } else {
                        double l = 0.299 * (c >> 16 & 255) + 0.587 * (c >> 8 & 255) + 0.114 * (c & 255);
                        int luma = 1 + (int) Math.max(0, Math.min(LUMA - 2, Math.round(l * (LUMA - 2) / 255)));
                        sym = luma * 8 + digits[k];
                    }
                    into[(y0 + y) * 128 + x0 + x] = symbol(sym);
                }
            }
        }
    }

    /** The decode tables, as GLSL array bodies. */
    public Map<String, String> glsl() {
        StringBuilder keys = new StringBuilder();
        StringBuilder ids = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : idByRgb.entrySet()) {
            if (keys.length() > 0) {
                keys.append(',');
                ids.append(',');
            }
            keys.append(e.getKey());
            ids.append(e.getValue());
        }
        StringBuilder syms = new StringBuilder();
        StringBuilder rgbs = new StringBuilder();
        for (int id = 0; id < 256; id++) {
            if (id > 0) {
                syms.append(',');
                rgbs.append(',');
            }
            syms.append(symbolOf[id]);
            rgbs.append(rgbById[id]);
        }
        // A hash table from a texel's colour to its map colour id, so the shader
        // reads one or two entries rather than binary-searching 244: every pixel of
        // every texture costs five of these lookups. Open addressing over 1024
        // slots, the multiplier picked for the shortest longest probe.
        long[] muls = {2654435761L, 2246822519L, 3266489917L, 668265263L, 374761393L, 2870177450L | 1, 1597334677L, 3812015801L};
        int bestProbes = Integer.MAX_VALUE;
        long bestMul = muls[0];
        long[] best = null;
        for (long mul : muls) {
            long[] table = new long[1024];
            java.util.Arrays.fill(table, 0xFFFFFFFFL);
            int longest = 0;
            for (Map.Entry<Integer, Integer> e : idByRgb.entrySet()) {
                long key = e.getKey() & 0xFFFFFFL;
                int h = (int) (((key * mul) & 0xFFFFFFFFL) >>> 22);
                int probes = 1;
                while (table[h] != 0xFFFFFFFFL) {
                    h = (h + 1) & 1023;
                    probes++;
                }
                table[h] = (key << 8) | (e.getValue() & 255);
                longest = Math.max(longest, probes);
            }
            if (longest < bestProbes) {
                bestProbes = longest;
                bestMul = mul;
                best = table;
            }
        }
        StringBuilder hash = new StringBuilder();
        for (int i = 0; i < best.length; i++) {
            if (i > 0) hash.append(',');
            hash.append(best[i]).append('u');
        }
        Map<String, String> out = new HashMap<>();
        out.put("@HASH@", hash.toString());
        out.put("@HASH_MUL@", Long.toString(bestMul));
        out.put("@HASH_PROBES@", Integer.toString(bestProbes));
        out.put("@COUNT@", Integer.toString(idByRgb.size()));
        out.put("@KEYS@", keys.toString());
        out.put("@IDS@", ids.toString());
        out.put("@SYMS@", syms.toString());
        out.put("@RGBS@", rgbs.toString());
        out.put("@MARK0@", Integer.toString(mark0));
        out.put("@MARK1@", Integer.toString(mark1));
        return out;
    }
}
