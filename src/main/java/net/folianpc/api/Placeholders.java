package net.folianpc.api;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.function.BiFunction;

public final class Placeholders {

    private static final long PAPI_RETRY_MILLIS = 5_000L;
    private static volatile Method papi;
    private static volatile long papiCheckedAt = Long.MIN_VALUE;

    private Placeholders() {
    }

    public static BiFunction<Player, String, String> standard() {
        return Placeholders::apply;
    }

    public static String apply(Player viewer, String text) {
        if (text == null || text.indexOf('%') < 0 || viewer == null) {
            return text;
        }
        String resolved = builtins(viewer, text);
        if (resolved.indexOf('%') < 0) {
            return resolved;
        }
        Method method = papi();
        if (method == null) {
            return resolved;
        }
        try {
            return (String) method.invoke(null, viewer, resolved);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return resolved;
        }
    }

    static String builtins(Player viewer, String text) {
        String resolved = text;
        if (resolved.contains("%player%")) {
            resolved = resolved.replace("%player%", viewer.getName());
        }
        if (resolved.contains("%online%")) {
            resolved = resolved.replace("%online%", String.valueOf(Bukkit.getOnlinePlayers().size()));
        }
        if (resolved.contains("%world%") && viewer.getWorld() != null) {
            resolved = resolved.replace("%world%", viewer.getWorld().getName());
        }
        return resolved;
    }

    public static boolean placeholderApiPresent() {
        return papi() != null;
    }

    private static Method papi() {
        Method method = papi;
        if (method != null) {
            return method;
        }
        long now = System.currentTimeMillis();
        if (now - papiCheckedAt < PAPI_RETRY_MILLIS) {
            return null;
        }
        papiCheckedAt = now;
        try {
            if (Bukkit.getServer() == null || Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
                return null;
            }
            Class<?> type = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            papi = type.getMethod("setPlaceholders", org.bukkit.OfflinePlayer.class, String.class);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            papi = null;
        }
        return papi;
    }
}
