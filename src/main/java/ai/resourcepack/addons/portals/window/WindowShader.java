package ai.resourcepack.addons.portals.window;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Writes the text shaders that draw the windows, with the server's map colour
 * table baked in, into the content folder's 26.2 overlay.
 *
 * <p>Written here rather than shipped finished because the table is the
 * client's, and reading it off the server is how it stays exactly right on the
 * version the server runs.
 */
public final class WindowShader {

    /** The overlay the core shaders live in: they only compile on 26.2. */
    public static final String OVERLAY = "resourcepack/portals_v88/assets/minecraft/shaders/core/";

    private WindowShader() {
    }

    /** @return whether anything changed, which is when the engine needs a reload. */
    public static boolean write(Path content, MapColours colours) throws IOException {
        String common = fill(read("window/common.glsl"), colours.glsl());
        boolean changed = false;
        for (String name : new String[]{"text.vsh", "text.fsh"}) {
            String text = read("window/" + name).replace("@COMMON@", common);
            Path file = content.resolve(OVERLAY + name);
            if (Files.isRegularFile(file) && Files.readString(file, StandardCharsets.UTF_8).equals(text)) continue;
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
            changed = true;
        }
        return changed;
    }

    private static String fill(String text, Map<String, String> values) {
        String out = text.replace("@BASE@", Integer.toString(WindowMaps.BASE));
        for (Map.Entry<String, String> e : values.entrySet()) out = out.replace(e.getKey(), e.getValue());
        return out;
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = WindowShader.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IOException("missing " + resource + " in the jar");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
