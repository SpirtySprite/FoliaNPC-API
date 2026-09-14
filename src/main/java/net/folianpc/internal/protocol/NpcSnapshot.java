package net.folianpc.internal.protocol;

import net.folianpc.api.MobVariant;
import org.bukkit.entity.Ageable;

import java.util.UUID;

public record NpcSnapshot(int entityId, UUID uuid, String name, String profileName,
                          boolean nametagVisible,
                          boolean glowing, boolean invisible, boolean skinLayers, double scale,
                          String glowColor, boolean collidable, boolean needsTeam,
                          String pose, boolean baby, MobVariant mobVariant,
                          org.bukkit.entity.EntityType type,
                          String world, double x, double y, double z,
                          float yaw, float pitch,
                          String skinValue, String skinSignature, boolean mirrorSkin,
                          boolean showInTabList,
                          java.util.Map<org.bukkit.inventory.EquipmentSlot,
                                  org.bukkit.inventory.ItemStack> equipment,
                          java.util.List<HologramLine> hologram,
                          java.util.Map<Integer, RawMeta> rawMeta) {

    public boolean isPlayer() {
        return type == org.bukkit.entity.EntityType.PLAYER;
    }

    public boolean isAgeable() {
        return Ageable.class.isAssignableFrom(type.getEntityClass());
    }
}
