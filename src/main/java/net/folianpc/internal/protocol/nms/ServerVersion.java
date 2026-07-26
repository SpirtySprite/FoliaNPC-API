package net.folianpc.internal.protocol.nms;

import org.bukkit.Bukkit;

// Version gate. Everything below is built for Mojang-mapped 1.20.6+, so refusing to start is kinder
// than half-working NPCs on an older server.
public final class ServerVersion {

    private static final int MINOR;
    private static final int PATCH;

    static {
        int minor = 21;
        int patch = 0;
        try {
            String number = Bukkit.getBukkitVersion().split("-", 2)[0];
            String[] parts = number.split("\\.");
            if (parts.length >= 2) minor = Integer.parseInt(parts[1]);
            if (parts.length >= 3) patch = Integer.parseInt(parts[2]);
        } catch (RuntimeException ignored) {
        }
        MINOR = minor;
        PATCH = patch;
    }

    private ServerVersion() {
    }

    public static int minor() {
        return MINOR;
    }

    public static boolean atLeast(int minMinor, int minPatch) {
        return MINOR > minMinor || (MINOR == minMinor && PATCH >= minPatch);
    }

    public static void requireSupported() {
        if (!atLeast(20, 6)) {
            throw new IllegalStateException("FoliaNPC requires Paper/Folia 1.20.6 or newer (found 1." + MINOR + "." + PATCH + ")");
        }
    }
}
