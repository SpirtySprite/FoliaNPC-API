package net.folianpc.internal.protocol;

public record HologramLine(int entityId, String text, double x, double y, double z,
                           net.folianpc.api.NametagStyle style) {
}
