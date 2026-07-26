package net.folianpc.api;

import java.util.UUID;

// A plain, serializable snapshot of an NPC. FoliaNPC does not persist these for you - store them
// however you like and re-create the NPCs on startup with FoliaNpc.spawn(data).
public record NpcData(UUID id, String name, org.bukkit.entity.EntityType type, String world,
                      double x, double y, double z, float yaw, float pitch,
                      boolean lookAtPlayers, Skin skin, boolean mirrorSkin,
                      java.util.Map<org.bukkit.inventory.EquipmentSlot,
                              org.bukkit.inventory.ItemStack> equipment,
                      java.util.List<String> nametag,
                      NpcAppearance appearance, NpcPose pose, boolean baby, boolean showInTabList,
                      MobVariant mobVariant, UUID owner) {
}
