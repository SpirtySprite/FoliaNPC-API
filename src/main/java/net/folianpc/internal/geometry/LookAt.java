package net.folianpc.internal.geometry;

// Yaw/pitch needed for an NPC at `from` to face `to`. Pure math, no Bukkit — unit tested.
public final class LookAt {

    // Degrees to the byte the protocol actually carries; also what change detection compares against.
    public static byte angleByte(float degrees) {
        return (byte) (int) (degrees * 256.0f / 360.0f);
    }

    public record Rotation(float yaw, float pitch) {
    }

    private LookAt() {
    }

    public static Rotation face(double fromX, double fromY, double fromZ,
                                double toX, double toY, double toZ) {
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        double distXZ = Math.sqrt(dx * dx + dz * dz);

        float yaw = normalizeYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, distXZ));
        return new Rotation(yaw, clampPitch(pitch));
    }

    // Minecraft yaw wraps to [-180, 180).
    public static float normalizeYaw(float yaw) {
        float y = yaw % 360f;
        if (y >= 180f) y -= 360f;
        if (y < -180f) y += 360f;
        return y;
    }

    private static float clampPitch(float pitch) {
        if (pitch > 90f) return 90f;
        if (pitch < -90f) return -90f;
        return pitch;
    }
}
