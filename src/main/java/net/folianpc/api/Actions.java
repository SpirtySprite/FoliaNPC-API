package net.folianpc.api;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

public final class Actions {

    private Actions() {
    }

    public static NpcAction message(String text) {
        return ctx -> ctx.player().sendMessage(Text.parse(fill(text, ctx)));
    }

    public static NpcAction message(net.kyori.adventure.text.Component text) {
        return ctx -> ctx.player().sendMessage(text);
    }

    public static NpcAction command(String command) {
        return ctx -> ctx.player().performCommand(strip(fill(command, ctx)));
    }

    public static NpcAction consoleCommand(String command) {
        return ctx -> {
            String resolved = strip(fill(command, ctx));
            ctx.runGlobal(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved));
        };
    }

    public static NpcAction teleport(Location target) {
        return ctx -> ctx.player().teleportAsync(target.clone());
    }

    public static NpcAction sound(Sound sound, float volume, float pitch) {
        return ctx -> ctx.player().playSound(ctx.player().getLocation(), sound, volume, pitch);
    }

    // Requires your plugin to register the "BungeeCord" outgoing channel first - FoliaNPC never
    // registers anything on your behalf.
    public static NpcAction connectToServer(String serverName) {
        return ctx -> {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            try {
                out.writeUTF("Connect");
                out.writeUTF(serverName);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            ctx.player().sendPluginMessage(ctx.plugin(), "BungeeCord", bytes.toByteArray());
        };
    }

    public static NpcAction requirePermission(String permission) {
        return ctx -> {
            if (!ctx.player().hasPermission(permission)) {
                ctx.cancelRemaining();
            }
        };
    }

    private static String fill(String text, NpcClickContext ctx) {
        return text.replace("%player%", ctx.player().getName()).replace("%npc%", ctx.npc().name());
    }

    private static String strip(String command) {
        return command.startsWith("/") ? command.substring(1) : command;
    }
}
