package ai.resourcepack.addons.portals.config;

import org.bukkit.configuration.file.FileConfiguration;

/** config.yml, read once per load. */
public record Settings(
        String object,
        double size,
        double lift,
        double ringRadius,
        double distance,
        int lifetimeTicks,
        int pairsPerPlayer,
        String soundOpen,
        String soundLoop,
        String soundClose,
        float soundVolume,
        int loopFirstTicks,
        int loopEveryTicks) {

    public static Settings read(FileConfiguration c) {
        double size = Math.max(1, c.getDouble("portal.size", 5.8));
        return new Settings(
                c.getString("portal.object", "portals:portal"),
                size,
                // The ring stands just over the ground: its code keeps its
                // sparks above 0.89 of the radius below the middle.
                c.getDouble("portal.lift", size / 2 * 0.89),
                c.getDouble("portal.ring-radius", size / 2 * 0.8),
                c.getDouble("portal.distance", 3.5),
                (int) Math.round(c.getDouble("portal.lifetime", 0) * 20),
                Math.max(1, c.getInt("portal.pairs-per-player", 1)),
                c.getString("sounds.open", "portals:portal_open"),
                c.getString("sounds.loop", "portals:portal_loop"),
                c.getString("sounds.close", "portals:portal_close"),
                (float) c.getDouble("sounds.volume", 1.5),
                c.getInt("sounds.loop-first", 57),
                Math.max(20, c.getInt("sounds.loop-every", 120)));
    }

}
