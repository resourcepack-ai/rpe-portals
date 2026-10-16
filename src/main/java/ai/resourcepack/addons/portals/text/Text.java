package ai.resourcepack.addons.portals.text;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.md_5.bungee.chat.ComponentSerializer;
import org.bukkit.command.CommandSender;

/**
 * How every message reaches a player, on Spigot and Paper alike. Messages are
 * MiniMessage, through the plugin's own copy of Adventure (shaded and
 * relocated: Spigot has none, and Paper's must not meet it), and leave as
 * BungeeCord chat components, which both servers take.
 */
public final class Text {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Text() {
    }

    public static void send(CommandSender to, String miniMessage) {
        to.spigot().sendMessage(ComponentSerializer.deserialize(GsonComponentSerializer.gson().serialize(MM.deserialize(miniMessage))));
    }
}
