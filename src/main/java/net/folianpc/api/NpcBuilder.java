package net.folianpc.api;

import org.bukkit.Location;

// Only location() is required; everything else mirrors a setter on the live Npc.
public final class NpcBuilder {

    private final FoliaNpc api;

    String name = "NPC";
    org.bukkit.entity.EntityType type = org.bukkit.entity.EntityType.PLAYER;
    String world = "world";
    double x;
    double y;
    double z;
    float yaw;
    float pitch;
    boolean lookAtPlayers;
    NpcClickListener listener;
    Skin skin;
    boolean mirrorSkin;
    java.util.UUID owner;
    final java.util.Map<org.bukkit.inventory.EquipmentSlot, org.bukkit.inventory.ItemStack> equipment =
            new java.util.EnumMap<>(org.bukkit.inventory.EquipmentSlot.class);
    final java.util.List<java.util.Map.Entry<ClickType, NpcAction>> actions = new java.util.ArrayList<>();
    java.util.List<String> nametag = java.util.List.of();
    long cooldown;
    double viewDistance;
    NpcAppearance appearance = NpcAppearance.defaults();
    NpcPose pose = NpcPose.STANDING;
    boolean baby;
    boolean showInTabList;
    MobVariant mobVariant = MobVariant.defaults();

    NpcBuilder(FoliaNpc api) {
        this.api = api;
    }

    public NpcBuilder name(String name) {
        this.name = name != null ? name : "NPC";
        return this;
    }

    public NpcBuilder location(Location location) {
        this.world = location.getWorld() != null ? location.getWorld().getName() : "world";
        this.x = location.getX();
        this.y = location.getY();
        this.z = location.getZ();
        this.yaw = location.getYaw();
        this.pitch = location.getPitch();
        return this;
    }

    public NpcBuilder lookAtPlayers(boolean enabled) {
        this.lookAtPlayers = enabled;
        return this;
    }

    public NpcBuilder onClick(NpcClickListener listener) {
        this.listener = listener;
        return this;
    }

    public NpcBuilder skin(Skin skin) {
        this.skin = skin;
        return this;
    }

    public NpcBuilder mirrorSkin(boolean enabled) {
        this.mirrorSkin = enabled;
        return this;
    }

    public NpcBuilder owner(java.util.UUID playerId) {
        this.owner = playerId;
        return this;
    }

    public NpcBuilder nametag(String... lines) {
        this.nametag = java.util.List.of(lines);
        return this;
    }

    public NpcBuilder action(ClickType type, NpcAction action) {
        this.actions.add(new java.util.AbstractMap.SimpleEntry<>(type, action));
        return this;
    }

    public NpcBuilder cooldown(long millis) {
        this.cooldown = millis;
        return this;
    }

    public NpcBuilder equipment(org.bukkit.inventory.EquipmentSlot slot, org.bukkit.inventory.ItemStack item) {
        this.equipment.put(slot, item);
        return this;
    }

    // Defaults to PLAYER.
    public NpcBuilder type(org.bukkit.entity.EntityType type) {
        this.type = type != null ? type : org.bukkit.entity.EntityType.PLAYER;
        return this;
    }

    public NpcBuilder glowing(boolean value) {
        return appearance(with(a -> new NpcAppearance(value, a.invisible(), a.skinLayers(), a.scale(),
                a.glowColor(), a.collidable(), a.nametagVisible())));
    }

    public NpcBuilder invisible(boolean value) {
        return appearance(with(a -> new NpcAppearance(a.glowing(), value, a.skinLayers(), a.scale(),
                a.glowColor(), a.collidable(), a.nametagVisible())));
    }

    public NpcBuilder skinLayers(boolean value) {
        return appearance(with(a -> new NpcAppearance(a.glowing(), a.invisible(), value, a.scale(),
                a.glowColor(), a.collidable(), a.nametagVisible())));
    }

    public NpcBuilder scale(double value) {
        return appearance(with(a -> new NpcAppearance(a.glowing(), a.invisible(), a.skinLayers(), value,
                a.glowColor(), a.collidable(), a.nametagVisible())));
    }

    public NpcBuilder glowColor(net.kyori.adventure.text.format.NamedTextColor color) {
        return appearance(with(a -> new NpcAppearance(a.glowing(), a.invisible(), a.skinLayers(), a.scale(),
                color, a.collidable(), a.nametagVisible())));
    }

    public NpcBuilder collidable(boolean value) {
        return appearance(with(a -> new NpcAppearance(a.glowing(), a.invisible(), a.skinLayers(), a.scale(),
                a.glowColor(), value, a.nametagVisible())));
    }

    public NpcBuilder appearance(NpcAppearance appearance) {
        this.appearance = appearance;
        return this;
    }

    public NpcBuilder pose(NpcPose pose) {
        this.pose = pose == null ? NpcPose.STANDING : pose;
        return this;
    }

    // Only takes effect on mobs that support an adult/baby state.
    public NpcBuilder baby(boolean value) {
        this.baby = value;
        return this;
    }

    // Player NPCs only. Off by default.
    public NpcBuilder showInTabList(boolean value) {
        this.showInTabList = value;
        return this;
    }

    public NpcBuilder variant(int value) {
        this.mobVariant = new MobVariant(value, mobVariant.variantName(), mobVariant.villagerProfession(),
                mobVariant.villagerType(), mobVariant.villagerLevel());
        return this;
    }

    public NpcBuilder variant(String name) {
        this.mobVariant = new MobVariant(mobVariant.variant(), name, mobVariant.villagerProfession(),
                mobVariant.villagerType(), mobVariant.villagerLevel());
        return this;
    }

    public NpcBuilder villagerProfession(String profession) {
        this.mobVariant = new MobVariant(mobVariant.variant(), mobVariant.variantName(), profession,
                mobVariant.villagerType(), mobVariant.villagerLevel());
        return this;
    }

    public NpcBuilder villagerType(String biomeType) {
        this.mobVariant = new MobVariant(mobVariant.variant(), mobVariant.variantName(),
                mobVariant.villagerProfession(), biomeType, mobVariant.villagerLevel());
        return this;
    }

    public NpcBuilder villagerLevel(int level) {
        this.mobVariant = new MobVariant(mobVariant.variant(), mobVariant.variantName(),
                mobVariant.villagerProfession(), mobVariant.villagerType(), level);
        return this;
    }

    public NpcBuilder mobVariant(MobVariant variant) {
        this.mobVariant = variant == null ? MobVariant.defaults() : variant;
        return this;
    }

    public NpcBuilder viewDistance(double blocks) {
        this.viewDistance = blocks;
        return this;
    }

    public Npc spawn() {
        return api.spawn(this);
    }

    private NpcAppearance with(java.util.function.UnaryOperator<NpcAppearance> change) {
        return change.apply(appearance);
    }
}
