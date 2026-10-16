package ai.resourcepack.addons.portals.platform;

/**
 * The plugin's own tick clock. Paper has one built in and Spigot does not, so
 * the plugin counts its own, from its once-a-tick task.
 */
public final class Ticks {

    private static int now;

    private Ticks() {
    }

    public static int now() {
        return now;
    }

    /** Called once a tick, first thing, by the plugin's timer. */
    public static void advance() {
        now++;
    }
}
