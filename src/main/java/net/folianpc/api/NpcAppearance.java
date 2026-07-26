package net.folianpc.api;

import net.kyori.adventure.text.format.NamedTextColor;

// Split out of NpcData so saving/restoring appearance doesn't need a fifteen-argument record.
public record NpcAppearance(boolean glowing, boolean invisible, boolean skinLayers, double scale,
                            NamedTextColor glowColor, boolean collidable, boolean nametagVisible) {

    public static NpcAppearance defaults() {
        return new NpcAppearance(false, false, true, 1.0, null, true, true);
    }
}
