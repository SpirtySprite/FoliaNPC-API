package net.folianpc.api;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

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

    public static NpcAction actionBar(String text) {
        return ctx -> ctx.player().sendActionBar(Text.parse(fill(text, ctx)));
    }

    public static NpcAction title(String title, String subtitle) {
        return title(title, subtitle, java.time.Duration.ofMillis(500), java.time.Duration.ofSeconds(3),
                java.time.Duration.ofMillis(500));
    }

    public static NpcAction title(String title, String subtitle, java.time.Duration fadeIn,
                                  java.time.Duration stay, java.time.Duration fadeOut) {
        return ctx -> ctx.player().showTitle(net.kyori.adventure.title.Title.title(
                Text.parse(fill(title, ctx)), Text.parse(fill(subtitle, ctx)),
                net.kyori.adventure.title.Title.Times.times(fadeIn, stay, fadeOut)));
    }

    public static NpcAction give(org.bukkit.inventory.ItemStack item) {
        org.bukkit.inventory.ItemStack template = item.clone();
        return ctx -> {
            Player player = ctx.player();
            for (org.bukkit.inventory.ItemStack leftover : player.getInventory().addItem(template.clone()).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        };
    }

    public static NpcAction emote(Emote emote) {
        return ctx -> ctx.npc().playEmote(emote);
    }

    public static NpcAction swing() {
        return ctx -> ctx.npc().swing();
    }

    public static NpcAction chance(double probability, NpcAction action) {
        double clamped = Math.max(0.0D, Math.min(1.0D, probability));
        return ctx -> {
            if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() < clamped) {
                action.run(ctx);
            }
        };
    }

    public static NpcAction random(NpcAction... actions) {
        if (actions.length == 0) {
            throw new IllegalArgumentException("random needs at least one action");
        }
        NpcAction[] copy = actions.clone();
        return ctx -> copy[java.util.concurrent.ThreadLocalRandom.current().nextInt(copy.length)].run(ctx);
    }

    public static NpcAction cooldown(java.time.Duration cooldown, NpcAction action, String deniedMessage) {
        long millis = Math.max(0L, cooldown.toMillis());
        java.util.Map<java.util.UUID, Long> last = new java.util.concurrent.ConcurrentHashMap<>();
        return ctx -> {
            long now = System.currentTimeMillis();
            java.util.UUID id = ctx.player().getUniqueId();
            boolean[] allowed = {false};
            long[] remaining = {0L};
            last.compute(id, (key, previous) -> {
                if (previous != null && now - previous < millis) {
                    remaining[0] = millis - (now - previous);
                    return previous;
                }
                allowed[0] = true;
                return now;
            });
            if (last.size() > 512) {
                last.values().removeIf(stamp -> now - stamp >= millis);
            }
            if (allowed[0]) {
                action.run(ctx);
                return;
            }
            ctx.cancelRemaining();
            if (deniedMessage != null && !deniedMessage.isEmpty()) {
                long seconds = Math.max(1L, (remaining[0] + 999L) / 1000L);
                ctx.player().sendMessage(Text.parse(fill(deniedMessage, ctx).replace("%seconds%", String.valueOf(seconds))));
            }
        };
    }

    public static NpcAction oncePerPlayer(NpcAction action) {
        java.util.Set<java.util.UUID> done = java.util.concurrent.ConcurrentHashMap.newKeySet();
        return ctx -> {
            if (done.add(ctx.player().getUniqueId())) {
                action.run(ctx);
            }
        };
    }

    public static NpcAction sequence(NpcAction... actions) {
        NpcAction[] copy = actions.clone();
        return ctx -> {
            for (NpcAction action : copy) {
                if (ctx.remainingCancelled()) {
                    return;
                }
                action.run(ctx);
            }
        };
    }

    public static NpcAction requireSneaking() {
        return ctx -> {
            if (!ctx.sneaking()) {
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
