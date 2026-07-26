package net.folianpc.internal.protocol;

// One floating line of text above an NPC, rendered as its own text-display entity.
public record HologramLine(int entityId, String text, double x, double y, double z) {
}
