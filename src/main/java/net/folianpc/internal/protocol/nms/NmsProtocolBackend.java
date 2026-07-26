package net.folianpc.internal.protocol.nms;

import net.folianpc.api.Capabilities;
import net.folianpc.internal.protocol.HologramLine;
import net.folianpc.internal.protocol.NpcSnapshot;
import net.folianpc.internal.protocol.ProtocolBackend;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.logging.Level;

public final class NmsProtocolBackend implements ProtocolBackend {

    private final Plugin plugin;
    private final String handlerName;
    private final Channels channels = new Channels();
    private final Map<String, Object> typeCache = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, String>> teamsByViewer = new ConcurrentHashMap<>();

    private final AtomicInteger entityCounter;
    private final Object playerType;
    private final Object vec3Zero;
    private final Method typeByName;

    private final Class<?> packetClass;
    private final Constructor<?> addEntityCtor;
    private final boolean byteAngles;
    private final Constructor<?> removeEntitiesCtor;
    private final Constructor<?> moveRotCtor;
    private final Constructor<?> movePosCtor;
    private final Class<?> rotateHeadClass;
    private final Field rotateHeadId;
    private final Field rotateHeadYaw;

    private final Profiles profiles;
    private final Interactions interactions;
    private final Metadata metadata;
    private final Appearance appearance;
    private final Displays displays;
    private final Teams teams;
    private final Equipment equipment;
    private final Animations animations;

    private volatile InteractSink sink = (viewer, id, type, sneaking) -> false;
    private volatile BiFunction<Player, String, String> nametagResolver = (viewer, text) -> text;
    private volatile boolean debug;
    private Method sendMethod;
    private final java.util.concurrent.atomic.LongAdder packetsSent = new java.util.concurrent.atomic.LongAdder();

    public void nametagResolver(BiFunction<Player, String, String> resolver) {
        this.nametagResolver = resolver == null ? (viewer, text) -> text : resolver;
    }

    public NmsProtocolBackend(Plugin plugin) {
        ServerVersion.requireSupported();
        this.plugin = plugin;
        this.handlerName = "folianpc_" + plugin.getName();

        Class<?> entity = Reflect.nms("world.entity", "Entity", "Entity");
        Class<?> entityType = Reflect.nms("world.entity", "EntityType", "EntityTypes");
        Class<?> vec3 = Reflect.nms("world.phys", "Vec3", "Vec3D");
        Class<?> addEntity = Reflect.nms("network.protocol.game",
                "ClientboundAddEntityPacket", "PacketPlayOutSpawnEntity");
        Class<?> removeEntities = Reflect.nms("network.protocol.game",
                "ClientboundRemoveEntitiesPacket", "PacketPlayOutEntityDestroy");
        Class<?> moveEntity = Reflect.nms("network.protocol.game",
                "ClientboundMoveEntityPacket", "PacketPlayOutEntity");

        this.packetClass = Reflect.nms("network.protocol", "Packet", "Packet");
        this.entityCounter = (AtomicInteger) Reflect.staticField(entity, "ENTITY_COUNTER");
        this.playerType = Reflect.staticField(entityType, "PLAYER");
        this.typeByName = Reflect.method(entityType, "byString", String.class);
        this.vec3Zero = Reflect.staticField(vec3, "ZERO");
        this.removeEntitiesCtor = Reflect.constructor(removeEntities, int[].class);
        this.moveRotCtor = Reflect.constructor(Nms.nested(moveEntity, "Rot", "d"),
                int.class, byte.class, byte.class, boolean.class);
        this.movePosCtor = Reflect.constructor(Nms.nested(moveEntity, "Pos", "a"),
                int.class, short.class, short.class, short.class, boolean.class);

        this.rotateHeadClass = Reflect.nms("network.protocol.game",
                "ClientboundRotateHeadPacket", "PacketPlayOutEntityHeadRotation");
        this.rotateHeadId = Reflect.fieldOfType(rotateHeadClass, int.class);
        this.rotateHeadYaw = Reflect.fieldOfType(rotateHeadClass, byte.class);

        Constructor<?> add;
        boolean bytes = true;
        try {
            add = Reflect.constructor(addEntity, int.class, UUID.class, double.class, double.class,
                    double.class, byte.class, byte.class, entityType, int.class, vec3, byte.class);
        } catch (RuntimeException e) {
            add = Reflect.constructor(addEntity, int.class, UUID.class, double.class, double.class,
                    double.class, float.class, float.class, entityType, int.class, vec3, double.class);
            bytes = false;
        }
        this.addEntityCtor = add;
        this.byteAngles = bytes;

        this.profiles = new Profiles();
        this.interactions = new Interactions();
        this.metadata = new Metadata();
        this.appearance = optional("appearance", () -> new Appearance(metadata, plugin));
        this.displays = optional("nametags", () -> new Displays(metadata));
        this.teams = optional("name plates", Teams::new);
        this.equipment = optional("equipment", Equipment::new);
        this.animations = optional("animations", Animations::new);
    }

    private <T> T optional(String feature, Supplier<T> factory) {
        try {
            return factory.get();
        } catch (RuntimeException e) {
            plugin.getLogger().warning("FoliaNPC: " + feature + " unavailable - " + e.getMessage());
            return null;
        }
    }

    public void setDebug(boolean value) {
        this.debug = value;
    }

    public Capabilities capabilities() {
        return new Capabilities(
                profiles.skinsSupported(),
                displays != null,
                teams != null,
                equipment != null,
                appearance != null && appearance.scaleSupported(),
                displays != null && displays.richText(),
                appearance != null && appearance.babySupported(),
                appearance != null && appearance.mobVariantsSupported(),
                appearance != null && appearance.villagerDataSupported());
    }

    @Override
    public int nextEntityId() {
        return entityCounter.incrementAndGet();
    }

    @Override
    public void show(Player viewer, NpcSnapshot npc) {
        if (npc.isPlayer()) {
            if (npc.mirrorSkin()) {
                String[] viewerSkin = viewerSkin(viewer);
                send(viewer, profiles.addPacket(npc, viewerSkin[0], viewerSkin[1]));
            } else {
                send(viewer, profiles.addPacket(npc));
            }
        }
        applyTeam(viewer, npc);
        send(viewer, addEntity(npc));
        updateMeta(viewer, npc);
        if (npc.scale() != 1.0) {
            scale(viewer, npc.entityId(), npc.scale());
        }
        equip(viewer, npc.entityId(), npc.equipment());
        showHologram(viewer, npc.hologram());
    }

    @Override
    public void hide(Player viewer, NpcSnapshot npc) {
        removeEntities(viewer, new int[]{npc.entityId()});
        send(viewer, profiles.removePacket(npc.uuid()));
        clearTeam(viewer, npc.uuid());
    }

    @Override
    public void look(Player viewer, int entityId, float yaw, float pitch) {
        send(viewer, Reflect.newInstance(moveRotCtor, entityId, Nms.angle(yaw), Nms.angle(pitch), true));
        send(viewer, rotateHead(entityId, Nms.angle(yaw)));
    }

    @Override
    public void move(Player viewer, int entityId, double dx, double dy, double dz) {
        send(viewer, Reflect.newInstance(movePosCtor, entityId, delta(dx), delta(dy), delta(dz), true));
    }

    private static short delta(double blocks) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(blocks * 4096.0)));
    }

    private void showHologram(Player viewer, List<HologramLine> lines) {
        if (displays == null) {
            return;
        }
        for (HologramLine line : lines) {
            send(viewer, spawn(line.entityId(), UUID.randomUUID(), textDisplayType(),
                    line.x(), line.y(), line.z()));
            send(viewer, displays.textPacket(line.entityId(), nametagResolver.apply(viewer, line.text())));
        }
    }

    @Override
    public void refreshHologram(Player viewer, NpcSnapshot npc) {
        if (displays == null) {
            return;
        }
        for (HologramLine line : npc.hologram()) {
            send(viewer, displays.textPacket(line.entityId(), nametagResolver.apply(viewer, line.text())));
        }
    }

    @Override
    public void animate(Player viewer, int entityId, int action) {
        if (animations != null) {
            send(viewer, animations.packet(entityId, action));
        }
    }

    @Override
    public void removeEntities(Player viewer, int[] entityIds) {
        if (entityIds.length > 0) {
            send(viewer, Reflect.newInstance(removeEntitiesCtor, (Object) entityIds));
        }
    }

    private void applyTeam(Player viewer, NpcSnapshot npc) {
        if (teams == null || !npc.needsTeam()) {
            return;
        }
        Map<UUID, String> sent = teamsByViewer.computeIfAbsent(
                viewer.getUniqueId(), id -> new ConcurrentHashMap<>());
        if (npc.profileName().equals(sent.put(npc.uuid(), npc.profileName()))) {
            return;
        }
        send(viewer, teams.packet(npc.profileName(), npc.nametagVisible(),
                npc.glowColor(), npc.collidable()));
    }

    private void clearTeam(Player viewer, UUID npcId) {
        Map<UUID, String> sent = teamsByViewer.get(viewer.getUniqueId());
        String teamFor = sent == null ? null : sent.remove(npcId);
        if (teamFor != null && teams != null) {
            send(viewer, teams.removePacket(teamFor));
        }
    }

    @Override
    public void updateMeta(Player viewer, NpcSnapshot npc) {
        if (appearance != null) {
            send(viewer, appearance.metadataPacket(npc));
        }
    }

    @Override
    public void scale(Player viewer, int entityId, double scale) {
        if (appearance != null) {
            send(viewer, appearance.scalePacket(entityId, scale));
        }
    }

    @Override
    public void equip(Player viewer, int entityId, Map<EquipmentSlot, ItemStack> items) {
        if (equipment != null && !items.isEmpty()) {
            send(viewer, equipment.packet(entityId, items));
        }
    }

    @Override
    public void injectViewer(Player viewer) {
        channels.inject(viewer, handlerName, msg -> onInbound(viewer, msg));
    }

    @Override
    public void ejectViewer(Player viewer) {
        teamsByViewer.remove(viewer.getUniqueId());
        channels.eject(viewer, handlerName);
    }

    @Override
    public void onInteract(InteractSink sink) {
        this.sink = sink;
    }

    private boolean onInbound(Player viewer, Object packet) {
        if (!interactions.isInteract(packet)) {
            return false;
        }
        Interactions.Click click = interactions.decode(packet);
        return click != null && sink.handle(viewer, click.entityId(), click.type(), click.sneaking());
    }

    private String[] viewerSkin(Player viewer) {
        PlayerProfile profile = viewer.getPlayerProfile();
        for (ProfileProperty property : profile.getProperties()) {
            if ("textures".equals(property.getName())) {
                return new String[]{property.getValue(), property.getSignature()};
            }
        }
        return new String[]{null, null};
    }

    private Object addEntity(NpcSnapshot npc) {
        return spawn(npc.entityId(), npc.uuid(), type(npc.type()), npc.x(), npc.y(), npc.z(),
                Nms.angle(npc.pitch()), Nms.angle(npc.yaw()));
    }

    private Object spawn(int entityId, UUID uuid, Object type, double x, double y, double z) {
        return spawn(entityId, uuid, type, x, y, z, (byte) 0, (byte) 0);
    }

    private Object spawn(int entityId, UUID uuid, Object type,
                         double x, double y, double z, byte pitch, byte yaw) {
        if (byteAngles) {
            return Reflect.newInstance(addEntityCtor, entityId, uuid, x, y, z,
                    pitch, yaw, type, 0, vec3Zero, yaw);
        }
        return Reflect.newInstance(addEntityCtor, entityId, uuid, x, y, z,
                (float) pitch, (float) yaw, type, 0, vec3Zero, (double) yaw);
    }

    private Object rotateHead(int entityId, byte yaw) {
        Object packet = Reflect.allocate(rotateHeadClass);
        Reflect.set(rotateHeadId, packet, entityId);
        Reflect.set(rotateHeadYaw, packet, yaw);
        return packet;
    }

    private Object textDisplayType() {
        return type(EntityType.TEXT_DISPLAY);
    }

    private Object type(EntityType type) {
        if (type == null || type == EntityType.PLAYER) {
            return playerType;
        }
        return typeCache.computeIfAbsent(type.getKey().getKey(), key -> {
            Object found = Reflect.invoke(typeByName, null, key);
            return ((Optional<?>) found).orElseThrow(
                    () -> new IllegalStateException("Unknown entity type '" + key + "'"));
        });
    }

    public long packetsSent() {
        return packetsSent.sum();
    }

    private void send(Player viewer, Object packet) {
        if (packet == null) {
            return;
        }
        packetsSent.increment();
        try {
            Object connection = channels.connection(viewer);
            if (sendMethod == null) {
                sendMethod = Reflect.method(connection.getClass(), "send", packetClass);
            }
            Reflect.invoke(sendMethod, connection, packet);
        } catch (Throwable e) {
            plugin.getLogger().log(debug ? Level.WARNING : Level.FINE,
                    "FoliaNPC: send failed for " + packet.getClass().getSimpleName(), e);
        }
    }
}
