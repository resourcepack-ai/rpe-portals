package ai.resourcepack.addons.portals.command;

import ai.resourcepack.addons.portals.PortalsPlugin;
import ai.resourcepack.addons.portals.api.PortalOptions;
import ai.resourcepack.addons.portals.api.PortalPlace;
import ai.resourcepack.addons.portals.place.Placer;
import ai.resourcepack.addons.portals.portal.Pair;
import ai.resourcepack.addons.portals.portal.PortalClicks;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * /portals: mark, place, toggle, remove and list for players; open,
 * closeall, reload and debug for staff. Every verb has its own permission.
 */
public final class PortalsCommand implements TabExecutor {

    /** How far away a portal can be and still be the one {@code toggle} and {@code remove} mean. */
    private static final double REACH = 12;

    /** Each verb and the node it needs, in the order the help lists them. */
    private static final Map<String, String> VERBS = new LinkedHashMap<>();

    static {
        VERBS.put("mark", Placer.MARK);
        VERBS.put("place", Placer.PLACE);
        VERBS.put("toggle", PortalClicks.TOGGLE);
        VERBS.put("remove", PortalClicks.REMOVE);
        VERBS.put("list", "portals.list");
        VERBS.put("open", "portals.open");
        VERBS.put("closeall", "portals.closeall");
        VERBS.put("reload", "portals.reload");
        VERBS.put("debug", "portals.debug");
    }

    private final PortalsPlugin plugin;

    public PortalsCommand(PortalsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String verb = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        String node = VERBS.get(verb);
        if (node == null) {
            help(sender, label);
            return true;
        }
        if (!allowed(sender, node)) return true;
        switch (verb) {
            case "mark" -> {
                Player player = player(sender);
                if (player == null) return true;
                plugin.placer().mark(player);
                say(sender, plugin.message("marked"));
            }
            case "place" -> place(sender, label, args);
            case "toggle" -> {
                Player player = player(sender);
                if (player == null) return true;
                Optional<Pair> pair = plugin.placer().nearest(player, REACH);
                if (pair.isEmpty()) {
                    say(sender, plugin.message("none-near"));
                    return true;
                }
                if (!mine(player, pair.get()) && !allowed(sender, PortalClicks.TOGGLE_OTHERS)) return true;
                plugin.portals().toggle(pair.get(), player);
            }
            case "remove" -> {
                Player player = player(sender);
                if (player == null) return true;
                if (args.length > 1 && args[1].equalsIgnoreCase("all")) {
                    List<Pair> mine = plugin.portals().ownedBy(player.getUniqueId());
                    for (Pair pair : mine) plugin.portals().takeAway(pair, player);
                    say(sender, plugin.message("removed-all").replace("{count}", Integer.toString(mine.size())));
                    return true;
                }
                Optional<Pair> pair = plugin.placer().nearest(player, REACH);
                if (pair.isEmpty()) {
                    say(sender, plugin.message("none-near"));
                    return true;
                }
                if (!mine(player, pair.get()) && !allowed(sender, PortalClicks.REMOVE_OTHERS)) return true;
                plugin.portals().takeAway(pair.get(), player);
                say(sender, plugin.message("removed"));
            }
            case "list" -> {
                Player player = player(sender);
                if (player == null) return true;
                List<Pair> mine = plugin.portals().ownedBy(player.getUniqueId());
                if (mine.isEmpty()) {
                    say(sender, plugin.message("list-empty"));
                    return true;
                }
                say(sender, plugin.message("list-header").replace("{count}", Integer.toString(mine.size())));
                for (Pair pair : mine) {
                    say(sender, plugin.message("list-line")
                            .replace("{here}", where(pair.here().middle()))
                            .replace("{there}", where(pair.there().middle()))
                            .replace("{state}", plugin.message(pair.isOpen() ? "state-open" : "state-shut")));
                }
            }
            case "open" -> open(sender, label, args);
            case "closeall" -> {
                int n = plugin.portals().pairs().size();
                plugin.portals().closeAll(sender instanceof Player p ? p : null);
                say(sender, plugin.message("closed-all").replace("{count}", Integer.toString(n)));
            }
            case "reload" -> {
                plugin.reloadSettings();
                say(sender, plugin.message("reloaded"));
            }
            case "debug" -> {
                for (String line : plugin.windows().describe()) sender.sendMessage(line);
            }
            default -> help(sender, label);
        }
        return true;
    }

    private void place(CommandSender sender, String label, String[] args) {
        Player player = player(sender);
        if (player == null) return;
        double size = 0;
        if (args.length > 1) {
            if (!allowed(sender, Placer.PLACE_SIZE)) return;
            try {
                size = Double.parseDouble(args[1]);
            } catch (NumberFormatException e) {
                say(sender, "<red>/" + label + " place [size]");
                return;
            }
            if (size < PortalOptions.MIN_SIZE || size > PortalOptions.MAX_SIZE) {
                say(sender, plugin.message("bad-size")
                        .replace("{min}", fmt(PortalOptions.MIN_SIZE)).replace("{max}", fmt(PortalOptions.MAX_SIZE)));
                return;
            }
        }
        switch (plugin.placer().place(player, size)) {
            case PLACED, CANCELLED -> {
            }
            case NO_DESTINATION -> say(sender, plugin.message("no-destination").replace("{label}", label));
            case OTHER_WORLD -> say(sender, plugin.message("other-world"));
            case TOO_CLOSE -> say(sender, plugin.message("too-close"));
            case NOT_LOADED -> say(sender, plugin.message("not-loaded"));
        }
    }

    /** /portals open <world> <x y z yaw> <x y z yaw> [size]: a pair at set places, for demos and command blocks. */
    private void open(CommandSender sender, String label, String[] args) {
        if (args.length != 10 && args.length != 11) {
            say(sender, "<red>/" + label + " open <world> <x> <y> <z> <yaw> <x> <y> <z> <yaw> [size]");
            return;
        }
        org.bukkit.World world = Bukkit.getWorld(args[1]);
        if (world == null) {
            say(sender, "<red>No world called " + args[1]);
            return;
        }
        Player by = sender instanceof Player p ? p : null;
        try {
            PortalOptions.Builder options = PortalOptions.builder()
                    .owner(by == null ? PortalOptions.NO_OWNER : by.getUniqueId());
            if (args.length == 11) options.size(Double.parseDouble(args[10]));
            plugin.portals().open(placeAt(world, args, 2), placeAt(world, args, 6), options.build(), by);
        } catch (NumberFormatException e) {
            say(sender, "<red>Coordinates, yaws and the size are numbers.");
        } catch (IllegalStateException e) {
            say(sender, plugin.message("not-loaded"));
        }
    }

    private static PortalPlace placeAt(org.bukkit.World world, String[] a, int i) {
        Location ground = new Location(world, Double.parseDouble(a[i]), Double.parseDouble(a[i + 1]), Double.parseDouble(a[i + 2]));
        return PortalPlace.standing(ground, Float.parseFloat(a[i + 3]));
    }

    private void help(CommandSender sender, String label) {
        say(sender, plugin.message("help-header"));
        for (Map.Entry<String, String> e : VERBS.entrySet()) {
            if (!sender.hasPermission(e.getValue())) continue;
            say(sender, plugin.message("help-" + e.getKey()).replace("{label}", label));
        }
    }

    private static boolean mine(Player player, Pair pair) {
        return pair.owner().equals(player.getUniqueId());
    }

    private Player player(CommandSender sender) {
        if (sender instanceof Player p) return p;
        say(sender, plugin.message("players-only"));
        return null;
    }

    private static String where(Location at) {
        return at.getWorld().getName() + " " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ();
    }

    private static String fmt(double v) {
        return v == Math.floor(v) ? Integer.toString((int) v) : Double.toString(v);
    }

    private boolean allowed(CommandSender sender, String node) {
        if (sender.hasPermission(node)) return true;
        say(sender, plugin.message("no-permission"));
        return false;
    }

    private static void say(CommandSender sender, String mini) {
        sender.sendMessage(MiniMessage.miniMessage().deserialize(mini));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            for (Map.Entry<String, String> e : VERBS.entrySet()) {
                if (e.getKey().startsWith(typed) && sender.hasPermission(e.getValue())) out.add(e.getKey());
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("remove") && "all".startsWith(typed)) {
            out.add("all");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("place") && sender.hasPermission(Placer.PLACE_SIZE)) {
            for (String s : List.of("3", "5.8", "8", "12")) if (s.startsWith(typed)) out.add(s);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("open")) {
            for (org.bukkit.World w : Bukkit.getWorlds()) {
                if (w.getName().toLowerCase(Locale.ROOT).startsWith(typed)) out.add(w.getName());
            }
        }
        return out;
    }
}
