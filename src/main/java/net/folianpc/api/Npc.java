package net.folianpc.api;

import java.util.UUID;

public interface Npc {

    UUID id();

    String name();

    Npc name(String name);

    Npc viewDistance(double blocks);

    double viewDistance();

    org.bukkit.entity.EntityType type();

    Npc type(org.bukkit.entity.EntityType type);

    String world();

    double x();

    double y();

    double z();

    Npc lookAtPlayers(boolean enabled);

    boolean lookAtPlayers();

    Npc onClick(NpcClickListener listener);

    Npc nametag(java.util.List<String> lines);

    java.util.List<String> nametag();

    Npc nametagVisible(boolean visible);

    boolean nametagVisible();

    Npc nametagStyle(NametagStyle style);

    NametagStyle nametagStyle();

    Npc glowing(boolean value);

    boolean glowing();

    Npc invisible(boolean value);

    boolean invisible();

    Npc skinLayers(boolean value);

    boolean skinLayers();

    Npc scale(double value);

    double scale();

    Npc metadata(int index, MetadataType type, Object value);

    Npc pose(NpcPose pose);

    NpcPose pose();

    Npc baby(boolean value);

    boolean baby();

    Npc variant(int value);

    int variant();

    Npc variant(String name);

    String variantName();

    Npc villagerProfession(String profession);

    String villagerProfession();

    Npc villagerType(String biomeType);

    String villagerType();

    Npc villagerLevel(int level);

    int villagerLevel();

    MobVariant mobVariant();

    Npc mobVariant(MobVariant variant);

    Npc swing();

    Npc swingOffHand();

    Npc playEmote(Emote emote);

    Npc onPlayerNear(double radius, java.util.function.BiConsumer<Npc, org.bukkit.entity.Player> callback);

    Npc onPlayerLeave(java.util.function.BiConsumer<Npc, org.bukkit.entity.Player> callback);

    Npc refreshNametag();

    Npc autoRefreshNametag(long everyTicks);

    Npc glowColor(net.kyori.adventure.text.format.NamedTextColor color);

    net.kyori.adventure.text.format.NamedTextColor glowColor();

    Npc collidable(boolean value);

    boolean collidable();

    Npc showInTabList(boolean value);

    boolean showInTabList();

    Npc showTo(UUID playerId);

    Npc hideFrom(UUID playerId);

    Npc resetVisibility(UUID playerId);

    default Npc showTo(org.bukkit.entity.Player player) {
        return showTo(player.getUniqueId());
    }

    default Npc hideFrom(org.bukkit.entity.Player player) {
        return hideFrom(player.getUniqueId());
    }

    default Npc resetVisibility(org.bukkit.entity.Player player) {
        return resetVisibility(player.getUniqueId());
    }

    NpcAppearance appearance();

    Npc appearance(NpcAppearance appearance);

    Npc addAction(ClickType type, NpcAction action);

    Npc addAction(ClickType type, NpcAction action, long delayTicks);

    Npc clearActions(ClickType type);

    Npc cooldown(long millis);

    long cooldown();

    Npc skin(Skin skin);

    Skin skin();

    Npc mirrorSkin(boolean enabled);

    boolean mirrorSkin();

    Npc equipment(org.bukkit.inventory.EquipmentSlot slot, org.bukkit.inventory.ItemStack item);

    java.util.Map<org.bukkit.inventory.EquipmentSlot, org.bukkit.inventory.ItemStack> equipment();

    NpcData data();

    Npc owner(UUID playerId);

    UUID owner();

    Npc copy(org.bukkit.Location at);

    Npc teleport(org.bukkit.Location target);

    Npc walkTo(org.bukkit.Location target, double blocksPerSecond);

    java.util.concurrent.CompletableFuture<Boolean> navigateTo(org.bukkit.Location target, double blocksPerSecond);

    Npc stopWalking();

    boolean moving();

    void remove();

    boolean removed();
}
