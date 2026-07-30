package net.folianpc.internal;

import net.folianpc.api.ClickType;
import net.folianpc.api.Emote;
import net.folianpc.api.Npc;
import net.folianpc.api.NpcAction;
import net.folianpc.api.NpcClickListener;
import net.folianpc.api.NpcAppearance;
import net.folianpc.api.NpcData;
import net.folianpc.api.MetadataType;
import net.folianpc.api.MobVariant;
import net.folianpc.api.NpcPose;
import net.folianpc.api.Skin;
import org.bukkit.entity.Player;
import net.folianpc.internal.geometry.LookAt;
import net.folianpc.internal.protocol.HologramLine;
import net.folianpc.internal.protocol.NpcSnapshot;
import net.folianpc.internal.protocol.RawMeta;
import org.bukkit.Location;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class NpcImpl implements Npc {

    private static final double HOLOGRAM_BASE = 2.05;
    private static final double HOLOGRAM_SPACING = 0.28;
    private static final int MAX_PROFILE_NAME = 16;

    public record ActionEntry(NpcAction action, long delayTicks) {
    }

    private final UUID uuid;
    private final int entityId;
    private volatile EntityType type;
    private final NpcManager manager;
    private volatile UUID owner;

    private volatile Position position;

    private volatile double[] walkTarget;
    private volatile double walkSpeed;
    private final Queue<double[]> waypoints = new ConcurrentLinkedQueue<>();

    private final Set<UUID> viewers = ConcurrentHashMap.newKeySet();
    private final Map<EquipmentSlot, ItemStack> equipment = new ConcurrentHashMap<>();
    private final Map<ClickType, List<ActionEntry>> actions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastInteract = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> visibility = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastLook = new ConcurrentHashMap<>();

    private volatile String name;
    private volatile Skin skin;
    private volatile boolean mirrorSkin;
    private volatile NpcClickListener clickListener;
    private volatile boolean lookAtPlayers;
    private volatile boolean removed;
    private volatile long cooldownMillis;

    private volatile List<String> nametag = List.of();
    private volatile int[] nametagIds = new int[0];
    private volatile boolean nametagVisible = true;
    private volatile boolean glowing;
    private volatile boolean invisible;
    private volatile boolean skinLayers = true;
    private volatile double scale = 1.0;
    private volatile NamedTextColor glowColor;
    private volatile boolean collidable = true;
    private volatile boolean showInTabList;

    private volatile NpcPose pose = NpcPose.STANDING;
    private volatile boolean baby;
    private volatile MobVariant mobVariant = MobVariant.defaults();
    private final Map<Integer, RawMeta> rawMeta = new ConcurrentHashMap<>();

    private volatile int nametagRefreshPasses;
    private int nametagRefreshCountdown;

    private volatile double viewDistance;

    private volatile double proximityRadius;
    private volatile BiConsumer<Npc, Player> nearCallback;
    private volatile BiConsumer<Npc, Player> leaveCallback;
    private final Set<UUID> nearby = ConcurrentHashMap.newKeySet();

    NpcImpl(UUID uuid, int entityId, String name, EntityType type, Position position, NpcManager manager) {
        this.uuid = uuid;
        this.entityId = entityId;
        this.name = name;
        this.type = type;
        this.position = position;
        this.manager = manager;
    }

    @Override
    public Npc teleport(Location target) {
        return teleportTo(toPosition(target));
    }

    @Override
    public Npc walkTo(Location target, double blocksPerSecond) {
        String world = target.getWorld() != null ? target.getWorld().getName() : "world";
        if (!world.equals(position.world())) {
            return teleportTo(toPosition(target));
        }
        return walkToward(target.getX(), target.getY(), target.getZ(), blocksPerSecond);
    }

    @Override
    public CompletableFuture<Boolean> navigateTo(Location target, double blocksPerSecond) {
        return manager.navigate(this, target, blocksPerSecond);
    }

    Npc teleportTo(Position target) {
        this.walkTarget = null;
        waypoints.clear();
        this.position = target;
        if (!removed) {
            manager.reposition(this);
        }
        return this;
    }

    Npc walkToward(double x, double y, double z, double blocksPerSecond) {
        waypoints.clear();
        this.walkSpeed = Math.max(0.05, blocksPerSecond);
        this.walkTarget = new double[]{x, y, z};
        return this;
    }

    Npc followRoute(List<double[]> route, double blocksPerSecond) {
        if (route.isEmpty()) {
            return this;
        }
        waypoints.clear();
        var legs = route.iterator();
        this.walkSpeed = Math.max(0.05, blocksPerSecond);
        this.walkTarget = legs.next();
        legs.forEachRemaining(waypoints::add);
        return this;
    }

    @Override
    public Npc stopWalking() {
        this.walkTarget = null;
        waypoints.clear();
        return this;
    }

    @Override
    public boolean moving() {
        return walkTarget != null;
    }

    double[] stepWalk(double seconds) {
        double[] target = walkTarget;
        if (target == null) {
            return null;
        }
        Position from = position;
        double dx = target[0] - from.x();
        double dy = target[1] - from.y();
        double dz = target[2] - from.z();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 1e-4) {
            walkTarget = waypoints.poll();
            return null;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        double step = walkSpeed * seconds;
        if (distance <= step) {
            this.position = new Position(from.world(), target[0], target[1], target[2], yaw, from.pitch());
            walkTarget = waypoints.poll();
            return new double[]{dx, dy, dz};
        }
        double f = step / distance;
        this.position = new Position(from.world(),
                from.x() + dx * f, from.y() + dy * f, from.z() + dz * f, yaw, from.pitch());
        return new double[]{dx * f, dy * f, dz * f};
    }

    private static Position toPosition(Location loc) {
        String world = loc.getWorld() != null ? loc.getWorld().getName() : "world";
        return new Position(world, loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
    }

    @Override
    public UUID id() {
        return uuid;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Npc name(String name) {
        this.name = name == null || name.isBlank() ? "NPC" : name;
        refresh(nametagIds);
        return this;
    }

    @Override
    public Npc viewDistance(double blocks) {
        this.viewDistance = blocks;
        return this;
    }

    @Override
    public double viewDistance() {
        return viewDistance;
    }

    @Override
    public EntityType type() {
        return type;
    }

    @Override
    public Npc type(EntityType type) {
        this.type = type != null ? type : EntityType.PLAYER;
        refresh(nametagIds);
        return this;
    }

    @Override
    public String world() {
        return position.world();
    }

    @Override
    public double x() {
        return position.x();
    }

    @Override
    public double y() {
        return position.y();
    }

    @Override
    public double z() {
        return position.z();
    }

    public int entityId() {
        return entityId;
    }

    public Position position() {
        return position;
    }

    Set<UUID> viewers() {
        return viewers;
    }

    NpcClickListener clickListener() {
        return clickListener;
    }

    @Override
    public Npc lookAtPlayers(boolean enabled) {
        this.lookAtPlayers = enabled;
        return this;
    }

    @Override
    public boolean lookAtPlayers() {
        return lookAtPlayers;
    }

    @Override
    public Npc onClick(NpcClickListener listener) {
        this.clickListener = listener;
        return this;
    }

    @Override
    public Npc skin(Skin skin) {
        this.skin = skin;
        refresh(nametagIds);
        return this;
    }

    @Override
    public Skin skin() {
        return skin;
    }

    @Override
    public Npc mirrorSkin(boolean enabled) {
        this.mirrorSkin = enabled;
        refresh(nametagIds);
        return this;
    }

    @Override
    public boolean mirrorSkin() {
        return mirrorSkin;
    }

    @Override
    public Npc equipment(EquipmentSlot slot, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            equipment.remove(slot);
        } else {
            equipment.put(slot, item.clone());
        }
        if (!removed) {
            manager.updateEquipment(this);
        }
        return this;
    }

    @Override
    public Map<EquipmentSlot, ItemStack> equipment() {
        return Map.copyOf(equipment);
    }

    @Override
    public Npc nametag(List<String> lines) {
        List<String> clean = lines == null ? List.of() : List.copyOf(lines);
        int[] stale = nametagIds;
        if (clean.size() != nametagIds.length) {
            int[] ids = new int[clean.size()];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = manager.newEntityId();
            }
            this.nametagIds = ids;
        }
        this.nametag = clean;
        this.nametagVisible = clean.isEmpty();
        refresh(stale);
        return this;
    }

    @Override
    public List<String> nametag() {
        return nametag;
    }

    @Override
    public Npc nametagVisible(boolean visible) {
        this.nametagVisible = visible;
        refresh(nametagIds);
        return this;
    }

    @Override
    public boolean nametagVisible() {
        return nametagVisible;
    }

    @Override
    public Npc glowing(boolean value) {
        this.glowing = value;
        return pushMeta();
    }

    @Override
    public boolean glowing() {
        return glowing;
    }

    @Override
    public Npc invisible(boolean value) {
        this.invisible = value;
        return pushMeta();
    }

    @Override
    public boolean invisible() {
        return invisible;
    }

    @Override
    public Npc skinLayers(boolean value) {
        this.skinLayers = value;
        return pushMeta();
    }

    @Override
    public boolean skinLayers() {
        return skinLayers;
    }

    @Override
    public Npc scale(double value) {
        this.scale = Math.max(0.0625, value);
        if (!removed) {
            manager.updateScale(this);
        }
        return this;
    }

    @Override
    public double scale() {
        return scale;
    }

    @Override
    public Npc metadata(int index, MetadataType type, Object value) {
        if (value == null) {
            rawMeta.remove(index);
        } else {
            rawMeta.put(index, new RawMeta(type, value));
        }
        return pushMeta();
    }

    @Override
    public Npc pose(NpcPose pose) {
        this.pose = pose == null ? NpcPose.STANDING : pose;
        return pushMeta();
    }

    @Override
    public NpcPose pose() {
        return pose;
    }

    @Override
    public Npc baby(boolean value) {
        this.baby = value;
        return pushMeta();
    }

    @Override
    public boolean baby() {
        return baby;
    }

    @Override
    public Npc variant(int value) {
        this.mobVariant = new MobVariant(value, mobVariant.variantName(), mobVariant.villagerProfession(),
                mobVariant.villagerType(), mobVariant.villagerLevel());
        return pushMeta();
    }

    @Override
    public int variant() {
        return mobVariant.variant();
    }

    @Override
    public Npc variant(String name) {
        this.mobVariant = new MobVariant(mobVariant.variant(), name, mobVariant.villagerProfession(),
                mobVariant.villagerType(), mobVariant.villagerLevel());
        return pushMeta();
    }

    @Override
    public String variantName() {
        return mobVariant.variantName();
    }

    @Override
    public Npc villagerProfession(String profession) {
        this.mobVariant = new MobVariant(mobVariant.variant(), mobVariant.variantName(), profession,
                mobVariant.villagerType(), mobVariant.villagerLevel());
        return pushMeta();
    }

    @Override
    public String villagerProfession() {
        return mobVariant.villagerProfession();
    }

    @Override
    public Npc villagerType(String biomeType) {
        this.mobVariant = new MobVariant(mobVariant.variant(), mobVariant.variantName(),
                mobVariant.villagerProfession(), biomeType, mobVariant.villagerLevel());
        return pushMeta();
    }

    @Override
    public String villagerType() {
        return mobVariant.villagerType();
    }

    @Override
    public Npc villagerLevel(int level) {
        this.mobVariant = new MobVariant(mobVariant.variant(), mobVariant.variantName(),
                mobVariant.villagerProfession(), mobVariant.villagerType(), level);
        return pushMeta();
    }

    @Override
    public int villagerLevel() {
        return mobVariant.villagerLevel();
    }

    @Override
    public MobVariant mobVariant() {
        return mobVariant;
    }

    @Override
    public Npc mobVariant(MobVariant variant) {
        this.mobVariant = variant == null ? MobVariant.defaults() : variant;
        return pushMeta();
    }

    @Override
    public Npc swing() {
        if (!removed) {
            manager.animate(this, 0);
        }
        return this;
    }

    @Override
    public Npc swingOffHand() {
        if (!removed) {
            manager.animate(this, 3);
        }
        return this;
    }

    @Override
    public Npc playEmote(Emote emote) {
        if (!removed && emote != null) {
            manager.playEmote(this, emote);
        }
        return this;
    }

    @Override
    public Npc onPlayerNear(double radius, BiConsumer<Npc, Player> callback) {
        this.proximityRadius = Math.max(0.0, radius);
        this.nearCallback = callback;
        return this;
    }

    @Override
    public Npc onPlayerLeave(BiConsumer<Npc, Player> callback) {
        this.leaveCallback = callback;
        return this;
    }

    boolean hasProximityListeners() {
        return proximityRadius > 0 && (nearCallback != null || leaveCallback != null);
    }

    double proximityRadius() {
        return proximityRadius;
    }

    BiConsumer<Npc, Player> nearCallback() {
        return nearCallback;
    }

    BiConsumer<Npc, Player> leaveCallback() {
        return leaveCallback;
    }

    void syncProximity(Set<UUID> nowNear, Consumer<UUID> onEnter, Consumer<UUID> onLeave) {
        for (UUID id : nowNear) {
            if (nearby.add(id)) {
                onEnter.accept(id);
            }
        }
        nearby.removeIf(id -> {
            if (!nowNear.contains(id)) {
                onLeave.accept(id);
                return true;
            }
            return false;
        });
    }

    @Override
    public Npc refreshNametag() {
        if (!removed) {
            manager.updateNametag(this);
        }
        return this;
    }

    @Override
    public Npc autoRefreshNametag(long everyTicks) {
        this.nametagRefreshPasses = everyTicks <= 0 ? 0 : Math.max(1, (int) (everyTicks / 2));
        this.nametagRefreshCountdown = nametagRefreshPasses;
        return this;
    }

    boolean dueForNametagRefresh() {
        if (nametagRefreshPasses <= 0 || nametag.isEmpty()) {
            return false;
        }
        if (--nametagRefreshCountdown > 0) {
            return false;
        }
        nametagRefreshCountdown = nametagRefreshPasses;
        return true;
    }

    @Override
    public Npc glowColor(NamedTextColor color) {
        this.glowColor = color;
        refresh(nametagIds);
        return this;
    }

    @Override
    public NamedTextColor glowColor() {
        return glowColor;
    }

    @Override
    public Npc collidable(boolean value) {
        this.collidable = value;
        refresh(nametagIds);
        return this;
    }

    @Override
    public boolean collidable() {
        return collidable;
    }

    @Override
    public Npc showInTabList(boolean value) {
        this.showInTabList = value;
        refresh(nametagIds);
        return this;
    }

    @Override
    public boolean showInTabList() {
        return showInTabList;
    }

    @Override
    public NpcAppearance appearance() {
        return new NpcAppearance(glowing, invisible, skinLayers, scale, glowColor, collidable, nametagVisible);
    }

    @Override
    public Npc appearance(NpcAppearance appearance) {
        this.glowing = appearance.glowing();
        this.invisible = appearance.invisible();
        this.skinLayers = appearance.skinLayers();
        this.scale = appearance.scale();
        this.glowColor = appearance.glowColor();
        this.collidable = appearance.collidable();
        this.nametagVisible = appearance.nametagVisible();
        refresh(nametagIds);
        return this;
    }

    @Override
    public Npc showTo(UUID playerId) {
        visibility.put(playerId, Boolean.TRUE);
        return this;
    }

    @Override
    public Npc hideFrom(UUID playerId) {
        visibility.put(playerId, Boolean.FALSE);
        return this;
    }

    @Override
    public Npc resetVisibility(UUID playerId) {
        visibility.remove(playerId);
        return this;
    }

    boolean visibleTo(UUID playerId, boolean inRange) {
        return visibility.getOrDefault(playerId, inRange);
    }

    boolean lookChanged(UUID playerId, float yaw, float pitch) {
        int packed = (LookAt.angleByte(yaw) & 0xFF) << 8 | (LookAt.angleByte(pitch) & 0xFF);
        Integer previous = lastLook.put(playerId, packed);
        return previous == null || previous != packed;
    }

    void forgetLook(UUID playerId) {
        lastLook.remove(playerId);
    }

    void forget(UUID playerId) {
        viewers.remove(playerId);
        lastInteract.remove(playerId);
        lastLook.remove(playerId);
        nearby.remove(playerId);
    }

    void dropVisibility(UUID playerId) {
        visibility.remove(playerId);
    }

    @Override
    public Npc addAction(ClickType type, NpcAction action) {
        return addAction(type, action, 0L);
    }

    @Override
    public Npc addAction(ClickType type, NpcAction action, long delayTicks) {
        if (action != null) {
            actions.computeIfAbsent(type, key -> new CopyOnWriteArrayList<>())
                    .add(new ActionEntry(action, Math.max(0L, delayTicks)));
        }
        return this;
    }

    @Override
    public Npc clearActions(ClickType type) {
        actions.remove(type);
        return this;
    }

    public List<ActionEntry> actions(ClickType type) {
        return actions.getOrDefault(type, List.of());
    }

    @Override
    public Npc cooldown(long millis) {
        this.cooldownMillis = Math.max(0L, millis);
        return this;
    }

    @Override
    public long cooldown() {
        return cooldownMillis;
    }

    boolean allowInteract(UUID viewer, long nowMillis) {
        if (cooldownMillis <= 0) {
            return true;
        }
        Long last = lastInteract.get(viewer);
        if (last != null && nowMillis - last < cooldownMillis) {
            return false;
        }
        lastInteract.put(viewer, nowMillis);
        return true;
    }

    boolean needsTeam() {
        return !nametagVisible || glowColor != null || !collidable;
    }

    // Teams match on player name, so a visible plate uses the display name but a hidden one needs a
    // unique id-derived name instead - otherwise it could collide with a real player's name.
    public String profileName() {
        String wire = needsTeam() ? uuid.toString().replace("-", "") : name;
        return wire.length() > MAX_PROFILE_NAME ? wire.substring(0, MAX_PROFILE_NAME) : wire;
    }

    int[] nametagIds() {
        return nametagIds;
    }

    @Override
    public void remove() {
        if (!removed) {
            removed = true;
            manager.unregister(this);
        }
    }

    @Override
    public boolean removed() {
        return removed;
    }

    public NpcSnapshot snapshot() {
        Skin current = skin;
        return new NpcSnapshot(entityId, uuid, name, profileName(), nametagVisible,
                glowing, invisible, skinLayers, scale,
                glowColor == null ? null : glowColor.toString(), collidable, needsTeam(),
                pose.name(), baby, mobVariant,
                type, position.world(),
                position.x(), position.y(), position.z(), position.yaw(), position.pitch(),
                current == null ? null : current.value(),
                current == null ? null : current.signature(), mirrorSkin, showInTabList,
                Map.copyOf(equipment), hologram(), Map.copyOf(rawMeta));
    }

    @Override
    public NpcData data() {
        return new NpcData(uuid, name, type, position.world(),
                position.x(), position.y(), position.z(), position.yaw(), position.pitch(),
                lookAtPlayers, skin, mirrorSkin, Map.copyOf(equipment), nametag, appearance(), pose,
                baby, showInTabList, mobVariant, owner);
    }

    @Override
    public Npc owner(UUID playerId) {
        this.owner = playerId;
        return this;
    }

    @Override
    public UUID owner() {
        return owner;
    }

    @Override
    public Npc copy(Location at) {
        NpcImpl clone = manager.create(UUID.randomUUID(), name, type, toPosition(at));
        clone.lookAtPlayers = lookAtPlayers;
        clone.clickListener = clickListener;
        clone.skin = skin;
        clone.mirrorSkin = mirrorSkin;
        clone.owner = owner;
        clone.cooldownMillis = cooldownMillis;
        clone.viewDistance = viewDistance;
        clone.showInTabList = showInTabList;
        clone.proximityRadius = proximityRadius;
        clone.nearCallback = nearCallback;
        clone.leaveCallback = leaveCallback;
        equipment.forEach(clone::equipment);
        actions.forEach((clickType, entries) ->
                entries.forEach(entry -> clone.addAction(clickType, entry.action(), entry.delayTicks())));
        clone.appearance(appearance());
        clone.pose(pose);
        clone.baby(baby);
        clone.mobVariant(mobVariant);
        if (!nametag.isEmpty()) {
            clone.nametag(nametag);
        }
        return clone;
    }

    private List<HologramLine> hologram() {
        List<String> lines = nametag;
        int[] ids = nametagIds;
        if (lines.isEmpty() || ids.length != lines.size()) {
            return List.of();
        }
        List<HologramLine> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            double y = position.y() + HOLOGRAM_BASE + (lines.size() - 1 - i) * HOLOGRAM_SPACING;
            out.add(new HologramLine(ids[i], lines.get(i), position.x(), y, position.z()));
        }
        return out;
    }

    private Npc pushMeta() {
        if (!removed) {
            manager.updateMeta(this);
        }
        return this;
    }

    private void refresh(int[] staleLineIds) {
        if (!removed) {
            manager.refresh(this, staleLineIds);
        }
    }
}
