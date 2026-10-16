package ai.resourcepack.addons.portals;

import ai.resourcepack.addons.portals.api.PortalsApi;
import ai.resourcepack.addons.portals.platform.Platform;
import ai.resourcepack.addons.portals.platform.Ticks;
import ai.resourcepack.addons.portals.command.PortalsCommand;
import ai.resourcepack.addons.portals.config.Settings;
import ai.resourcepack.addons.portals.portal.PortalClicks;
import ai.resourcepack.addons.portals.portal.Portals;
import ai.resourcepack.addons.portals.place.Placer;
import ai.resourcepack.addons.portals.travel.Crossings;
import ai.resourcepack.addons.portals.window.BlockLooks;
import ai.resourcepack.addons.portals.window.ClientAssets;
import ai.resourcepack.addons.portals.window.MapColours;
import ai.resourcepack.addons.portals.window.WindowMaps;
import ai.resourcepack.addons.portals.window.WindowShader;
import ai.resourcepack.engine.RPEnginePlugin;
import ai.resourcepack.engine.api.AddonContent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

/**
 * Portals: rings of sparks that open onto somewhere else. Mark a place,
 * open a portal in front of you, see the other place through it, walk through.
 *
 * <p>The ring is a 3D shader object RP Engine draws; this plugin decides where
 * the two of a pair stand, writes the far side into maps that the pack's text
 * shader draws in the hole ({@link WindowMaps}) and carries players across
 * ({@link Crossings}).
 */
public final class PortalsPlugin extends JavaPlugin implements Listener {

    private static final String NS = "portals";

    private Settings settings;
    private Portals portals;
    private WindowMaps windows;
    private Crossings crossings;
    private Placer placer;
    private PortalClicks clicks;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = Settings.read(getConfig());
        RPEnginePlugin engine = (RPEnginePlugin) Objects.requireNonNull(getServer().getPluginManager().getPlugin("RPEngine"));
        MapColours colours = new MapColours();
        try {
            boolean changed = AddonContent.install(this, engine, NS);
            // The window shaders carry this server's map colour table.
            changed |= WindowShader.write(engine.getDataFolder().toPath().resolve("content").resolve(NS), colours);
            if (changed) {
                engine.reload();
            }
        } catch (Exception e) {
            getLogger().severe("Could not install the Portals content: " + e.getMessage());
        }
        if (!engine.shaderObjects().supported()) {
            getLogger().warning("This server cannot place 3D shader objects (Minecraft 1.21.4+ is needed); the portals will not draw.");
        }
        portals = new Portals(this, engine.shaderObjects(), settings);
        ClientAssets assets = new ClientAssets();
        BlockLooks looks = new BlockLooks(assets);
        windows = new WindowMaps(this, colours, looks);
        windows.sweep();
        portals.windows(windows);
        // Real block textures, once the game's own files are to hand.
        assets.load(this, getConfig().getBoolean("textures.download", true), () -> {
            looks.reset();
            windows.redrawAll();
        });
        crossings = new Crossings(portals);
        placer = new Placer(this, portals, settings);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(portals, this);
        // Rings an older version saved with the world, before any pair exists.
        getServer().getScheduler().runTask(this, () -> {
            int stray = portals.sweep();
            if (stray > 0) getLogger().info("Took away " + stray + " stray portal ring(s).");
        });
        getServer().getPluginManager().registerEvents(crossings, this);
        clicks = new PortalClicks(portals, windows, getConfig());
        getServer().getPluginManager().registerEvents(clicks, this);
        PortalsCommand command = new PortalsCommand(this);
        Objects.requireNonNull(getCommand("portals")).setExecutor(command);
        Objects.requireNonNull(getCommand("portals")).setTabCompleter(command);
        getServer().getScheduler().runTaskTimer(this, () -> {
            Ticks.advance();
            Platform.tick();
            portals.tick();
            windows.tick();
        }, 1L, 1L);
        getServer().getServicesManager().register(PortalsApi.class, new PortalsService(this), this, ServicePriority.Normal);
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (portals != null) portals.closeAllNow();
        if (windows != null) windows.closeAll();
    }

    public void reloadSettings() {
        reloadConfig();
        settings = Settings.read(getConfig());
        portals.settings(settings);
        placer.settings(settings);
        clicks.messages(getConfig());
    }

    public Settings settings() {
        return settings;
    }

    public WindowMaps windows() {
        return windows;
    }

    public Portals portals() {
        return portals;
    }

    public Placer placer() {
        return placer;
    }

    public String message(String key) {
        // Without a default here, so a key an older config.yml lacks falls back to
        // the jar's own text rather than to the key itself.
        String text = getConfig().getString("messages." + key);
        return text == null ? key : text;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        crossings.forget(event.getPlayer().getUniqueId());
    }
}
