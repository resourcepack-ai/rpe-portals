package ai.resourcepack.addons.portals.window;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The game's own block models and textures, read from the client jar, so the
 * window draws a block with the texture the player knows rather than a colour.
 *
 * <p>A server jar has none of them, so the client jar for the server's version
 * is downloaded from Mojang once, into this plugin's folder, the way map
 * renderers like BlueMap do it: they are Mojang's files, fetched from Mojang,
 * and never shipped in this plugin. {@code textures.download: false} turns that
 * off, and the windows then draw each block in its map colour.
 */
public final class ClientAssets {

    private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    private volatile ZipFile jar;

    /** Opens the jar if it is here, and fetches it in the background if allowed and it is not. */
    public void load(Plugin plugin, boolean download, Runnable whenReady) {
        String version = Bukkit.getMinecraftVersion();
        File file = new File(plugin.getDataFolder(), "cache/client-" + version + ".jar");
        if (file.isFile() && open(plugin, file)) {
            whenReady.run();
            return;
        }
        if (!download) {
            plugin.getLogger().info("Block textures are off (textures.download: false): windows draw blocks in their map colours.");
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.getLogger().info("Downloading the Minecraft " + version + " client from Mojang for its block textures (once)...");
                fetch(version, file);
                if (open(plugin, file)) {
                    Bukkit.getScheduler().runTask(plugin, whenReady);
                }
            } catch (IOException | RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Could not download the client jar; windows draw blocks in their map colours.", e);
            }
        });
    }

    private boolean open(Plugin plugin, File file) {
        try {
            jar = new ZipFile(file);
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("The cached client jar is unreadable (" + e.getMessage() + "); delete it to download it again.");
            return false;
        }
    }

    private static void fetch(String version, File to) throws IOException {
        JsonObject manifest = json(MANIFEST);
        String url = null;
        for (var v : manifest.getAsJsonArray("versions")) {
            JsonObject o = v.getAsJsonObject();
            if (o.get("id").getAsString().equals(version)) {
                url = o.get("url").getAsString();
                break;
            }
        }
        if (url == null) throw new IOException("Mojang's manifest has no version " + version);
        String client = json(url).getAsJsonObject("downloads").getAsJsonObject("client").get("url").getAsString();
        to.getParentFile().mkdirs();
        File part = new File(to.getPath() + ".part");
        try (InputStream in = URI.create(client).toURL().openStream()) {
            Files.copy(in, part.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(part.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static JsonObject json(String url) throws IOException {
        try (InputStream in = URI.create(url).toURL().openStream()) {
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    public boolean ready() {
        return jar != null;
    }

    /** {@code assets/minecraft/<path>} as JSON, if the jar has it. */
    public Optional<JsonObject> readJson(String path) {
        ZipFile z = jar;
        if (z == null) return Optional.empty();
        ZipEntry e = z.getEntry("assets/minecraft/" + path);
        if (e == null) return Optional.empty();
        try (InputStream in = z.getInputStream(e)) {
            return Optional.of(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (IOException | RuntimeException ex) {
            return Optional.empty();
        }
    }

    /** {@code assets/minecraft/<path>} as an image, if the jar has it. */
    public Optional<BufferedImage> image(String path) {
        ZipFile z = jar;
        if (z == null) return Optional.empty();
        ZipEntry e = z.getEntry("assets/minecraft/" + path);
        if (e == null) return Optional.empty();
        try (InputStream in = z.getInputStream(e)) {
            return Optional.ofNullable(ImageIO.read(in));
        } catch (IOException ex) {
            return Optional.empty();
        }
    }
}
