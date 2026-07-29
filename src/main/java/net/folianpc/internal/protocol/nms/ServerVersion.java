package net.folianpc.internal.protocol.nms;

import org.bukkit.Bukkit;

// Version gate. Everything below is built for Mojang-mapped 1.20.6+, so refusing to start is kinder
// than half-working NPCs on an older server.
public final class ServerVersion {

    private static final boolean CALENDAR;
    private static final int MAJOR;
    private static final int MINOR;
    private static final int PATCH;

    static {
        boolean calendar = false;
        int major = 1;
        int minor = 21;
        int patch = 0;
        try {
            String number = Bukkit.getBukkitVersion().split("-", 2)[0];
            String[] parts = number.split("\\.");
            int[] numeric = new int[Math.min(parts.length, 3)];
            int count = 0;
            while (count < numeric.length) {
                try {
                    numeric[count] = Integer.parseInt(parts[count]);
                } catch (NumberFormatException nonNumeric) {
                    break;
                }
                count++;
            }
            if (count >= 1) major = numeric[0];
            if (count >= 2) minor = numeric[1];
            if (count >= 3) patch = numeric[2];
            calendar = count >= 1 && major != 1;
        } catch (RuntimeException ignored) {
        }
        CALENDAR = calendar;
        MAJOR = major;
        MINOR = minor;
        PATCH = patch;
    }

    private ServerVersion() {
    }

    public static int minor() {
        return MINOR;
    }

    public static boolean atLeast(int minMinor, int minPatch) {
        if (CALENDAR) {
            return true;
        }
        return MINOR > minMinor || (MINOR == minMinor && PATCH >= minPatch);
    }

    public static void requireSupported() {
        if (!atLeast(20, 6)) {
            throw new IllegalStateException("FoliaNPC requires Paper/Folia 1.20.6 or newer (found " + describe() + ")");
        }
    }

    private static String describe() {
        return CALENDAR ? (MAJOR + "." + MINOR + (PATCH > 0 ? "." + PATCH : "")) : ("1." + MINOR + "." + PATCH);
    }
}
