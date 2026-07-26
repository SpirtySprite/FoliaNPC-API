package net.folianpc.internal.protocol.nms;

import net.folianpc.internal.protocol.NpcSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

final class Appearance {

    private final Plugin plugin;

    private static final byte INVISIBLE = 0x20;
    private static final byte GLOWING = 0x40;
    private static final byte ALL_SKIN_LAYERS = 0x7f;

    private final Metadata metadata;
    private final int flagsIndex;
    private final int skinPartsIndex;

    private Constructor<?> scaleSnapshotCtor;
    private Constructor<?> scalePacketCtor;
    private Object scaleAttribute;

    private Class<?> poseClass;
    private Object poseSerializer;
    private int poseIndex = -1;

    private int babyIndex = -1;

    private int rabbitIndex = -1;
    private int parrotIndex = -1;
    private int axolotlIndex = -1;
    private int mooshroomIndex = -1;
    private int horseIndex = -1;

    private int catIndex = -1;
    private Object catSerializer;
    private Object catRegistry;
    private int wolfIndex = -1;
    private Object wolfSerializer;
    private Object wolfRegistry;
    private int frogIndex = -1;
    private Object frogSerializer;
    private Object frogRegistry;

    private int villagerDataIndex = -1;
    private Object villagerDataSerializer;
    private Constructor<?> villagerDataCtor;
    private Object villagerTypeRegistry;
    private Object villagerProfessionRegistry;

    private Class<?> identifierClass;
    private Method identifierWithDefaultNamespace;
    private Method registryGet;
    private Object registryAccess;

    Appearance(Metadata metadata, Plugin plugin) {
        this.metadata = metadata;
        this.plugin = plugin;
        Class<?> entity = Reflect.nms("world.entity", "Entity", "Entity");
        Class<?> player = Reflect.nms("world.entity.player", "Player", "EntityHuman");
        this.flagsIndex = Metadata.indexOf(Reflect.staticField(entity, "DATA_SHARED_FLAGS_ID"));
        this.skinPartsIndex = Metadata.indexOf(Reflect.staticField(player, "DATA_PLAYER_MODE_CUSTOMISATION"));
        resolveScale();
        resolvePose(entity);
        resolveBaby();
        resolveRegistryTools();
        resolveSimpleVariants();
        resolveHolderVariants();
        resolveVillagerData();
    }

    Object metadataPacket(NpcSnapshot npc) {
        List<Object> values = new ArrayList<>(3);
        byte flags = 0;
        if (npc.invisible()) {
            flags |= INVISIBLE;
        }
        if (npc.glowing()) {
            flags |= GLOWING;
        }
        values.add(metadata.value(flagsIndex, metadata.byteSerializer, flags));
        if (npc.isPlayer()) {
            values.add(metadata.value(skinPartsIndex, metadata.byteSerializer,
                    npc.skinLayers() ? ALL_SKIN_LAYERS : (byte) 0));
        }
        Object pose = nmsPose(npc.pose());
        if (pose != null) {
            values.add(metadata.value(poseIndex, poseSerializer, pose));
        }
        if (babyIndex >= 0 && npc.isAgeable()) {
            values.add(metadata.value(babyIndex, metadata.booleanSerializer, npc.baby()));
        }
        addVariant(values, npc);
        addVillagerData(values, npc);
        npc.rawMeta().forEach((index, raw) -> addRawMeta(values, index, raw));
        return metadata.packet(npc.entityId(), values);
    }

    private void addVariant(List<Object> values, NpcSnapshot npc) {
        int intVariant = npc.mobVariant().variant();
        String name = npc.mobVariant().variantName();
        switch (npc.type()) {
            case RABBIT -> addSimple(values, rabbitIndex, intVariant);
            case PARROT -> addSimple(values, parrotIndex, intVariant);
            case AXOLOTL -> addSimple(values, axolotlIndex, intVariant);
            case MOOSHROOM -> addSimple(values, mooshroomIndex, intVariant);
            case HORSE -> addSimple(values, horseIndex, intVariant);
            case CAT -> addHolder(values, catIndex, catSerializer, catRegistry, name);
            case WOLF -> addHolder(values, wolfIndex, wolfSerializer, wolfRegistry, name);
            case FROG -> addHolder(values, frogIndex, frogSerializer, frogRegistry, name);
            default -> {
            }
        }
    }

    private void addSimple(List<Object> values, int index, int value) {
        if (index >= 0) {
            values.add(metadata.value(index, metadata.intSerializer, value));
        }
    }

    private void addHolder(List<Object> values, int index, Object serializer, Object registry, String name) {
        if (index < 0 || registry == null || name == null) {
            return;
        }
        Object holder = holderByName(registry, name);
        if (holder != null) {
            values.add(metadata.value(index, serializer, holder));
        }
    }

    private void addVillagerData(List<Object> values, NpcSnapshot npc) {
        if (villagerDataIndex < 0 || npc.type() != EntityType.VILLAGER) {
            return;
        }
        String typeName = npc.mobVariant().villagerType();
        String professionName = npc.mobVariant().villagerProfession();
        if (typeName == null || professionName == null) {
            return;
        }
        Object type = holderByName(villagerTypeRegistry, typeName);
        Object profession = holderByName(villagerProfessionRegistry, professionName);
        if (type == null || profession == null) {
            return;
        }
        try {
            int level = Math.max(1, npc.mobVariant().villagerLevel());
            Object data = Reflect.newInstance(villagerDataCtor, type, profession, level);
            values.add(metadata.value(villagerDataIndex, villagerDataSerializer, data));
        } catch (RuntimeException e) {
            plugin.getLogger().warning("FoliaNPC: failed to build villager data - " + e);
        }
    }

    private Object holderByName(Object registry, String name) {
        try {
            Object id = Reflect.invoke(identifierWithDefaultNamespace, null, name);
            Method get = registryGetMethod(registry);
            Object opt = Reflect.invoke(get, registry, id);
            Object holder = ((Optional<?>) opt).orElse(null);
            if (holder == null) {
                plugin.getLogger().warning("FoliaNPC: no registry entry named '" + name + "'");
            }
            return holder;
        } catch (RuntimeException e) {
            plugin.getLogger().warning("FoliaNPC: registry lookup for '" + name + "' failed - " + e);
            return null;
        }
    }

    // Discovered from the actual registry instance's own runtime class rather than by resolving
    // "net.minecraft.core.Registry" by name - that name-based lookup was somehow resolving to the
    // wrong class entirely on at least one server build, for reasons that were never pinned down.
    // Going through a live instance sidesteps whatever that was.
    private Method registryGetMethod(Object registry) {
        if (registryGet != null) {
            return registryGet;
        }
        for (Method m : registry.getClass().getMethods()) {
            if (m.getName().equals("get") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == identifierClass) {
                m.setAccessible(true);
                registryGet = m;
                return m;
            }
        }
        throw new IllegalStateException("No get(" + identifierClass.getSimpleName() + ") on " + registry.getClass());
    }

    private void addRawMeta(List<Object> values, int index, net.folianpc.internal.protocol.RawMeta raw) {
        Object serializer = serializerFor(raw.type());
        if (serializer == null) {
            return;
        }
        try {
            values.add(metadata.value(index, serializer, coerce(raw.type(), raw.value())));
        } catch (RuntimeException ignored) {
        }
    }

    private Object serializerFor(net.folianpc.api.MetadataType type) {
        return switch (type) {
            case BYTE -> metadata.byteSerializer;
            case INT -> metadata.intSerializer;
            case BOOLEAN -> metadata.booleanSerializer;
            case FLOAT -> metadata.floatSerializer;
        };
    }

    private static Object coerce(net.folianpc.api.MetadataType type, Object value) {
        return switch (type) {
            case BYTE -> ((Number) value).byteValue();
            case INT -> ((Number) value).intValue();
            case FLOAT -> ((Number) value).floatValue();
            case BOOLEAN -> value instanceof Boolean b ? b : ((Number) value).intValue() != 0;
        };
    }

    private Object nmsPose(String name) {
        if (poseIndex < 0 || name == null) {
            return null;
        }
        try {
            return Reflect.enumConstant(poseClass, name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void resolvePose(Class<?> entity) {
        try {
            Class<?> serializers = Reflect.nms("network.syncher", "EntityDataSerializers", "DataWatcherRegistry");
            this.poseClass = Reflect.nms("world.entity", "Pose", "EntityPose");
            this.poseSerializer = Reflect.staticField(serializers, "POSE");
            this.poseIndex = Metadata.indexOf(Reflect.staticField(entity, "DATA_POSE"));
        } catch (RuntimeException e) {
            this.poseIndex = -1;
        }
    }

    boolean babySupported() {
        return babyIndex >= 0;
    }

    private void resolveBaby() {
        try {
            Class<?> ageableMob = Reflect.nms("world.entity", "AgeableMob", "EntityAgeable");
            this.babyIndex = Metadata.indexOf(Reflect.staticField(ageableMob, "DATA_BABY_ID"));
        } catch (RuntimeException e) {
            this.babyIndex = -1;
        }
    }

    boolean mobVariantsSupported() {
        return rabbitIndex >= 0 || parrotIndex >= 0 || axolotlIndex >= 0 || mooshroomIndex >= 0
                || horseIndex >= 0 || catIndex >= 0 || wolfIndex >= 0 || frogIndex >= 0;
    }

    boolean villagerDataSupported() {
        return villagerDataIndex >= 0;
    }

    // The get(Identifier) method itself is deliberately not resolved here by asking for the Registry
    // class by name - see registryGetMethod, which discovers it from a live instance instead.
    private void resolveRegistryTools() {
        try {
            Class<?> id = Reflect.nms("resources", "Identifier", "ResourceLocation");
            this.identifierClass = id;
            this.identifierWithDefaultNamespace = Reflect.method(id, "withDefaultNamespace", String.class);
        } catch (RuntimeException e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "FoliaNPC: registry tools unavailable, mob variants/villager data disabled", e);
            this.identifierClass = null;
        }
    }

    private Object registryAccess() {
        if (registryAccess != null) {
            return registryAccess;
        }
        Object craftServer = Bukkit.getServer();
        Object minecraftServer = Reflect.invoke(Reflect.method(craftServer.getClass(), "getServer"), craftServer);
        Object access = Reflect.invoke(Reflect.method(minecraftServer.getClass(), "registryAccess"), minecraftServer);
        this.registryAccess = access;
        return access;
    }

    private Object registryFor(Object resourceKey) {
        Object access = registryAccess();
        Method lookupOrThrow = findLookupOrThrowReturningRegistry(access.getClass());
        return Reflect.invoke(lookupOrThrow, access, resourceKey);
    }

    // RegistryAccess has three same-erasure overloads of lookupOrThrow(ResourceKey); only the one
    // actually returning Registry is any use here, and return type is the only way to tell them apart.
    private static Method findLookupOrThrowReturningRegistry(Class<?> registryAccessClass) {
        for (Method m : registryAccessClass.getMethods()) {
            if (m.getName().equals("lookupOrThrow") && m.getParameterCount() == 1
                    && m.getReturnType().getSimpleName().equals("Registry")) {
                m.setAccessible(true);
                return m;
            }
        }
        throw new IllegalStateException("No lookupOrThrow(ResourceKey) -> Registry found");
    }

    private void resolveSimpleVariants() {
        rabbitIndex = simpleIndex(new String[]{"world.entity.animal.rabbit", "world.entity.animal"},
                "Rabbit", "DATA_TYPE_ID");
        parrotIndex = simpleIndex(new String[]{"world.entity.animal.parrot", "world.entity.animal"},
                "Parrot", "DATA_VARIANT_ID");
        axolotlIndex = simpleIndex(new String[]{"world.entity.animal.axolotl", "world.entity.animal"},
                "Axolotl", "DATA_VARIANT");
        mooshroomIndex = simpleIndex(new String[]{"world.entity.animal.cow", "world.entity.animal"},
                "MushroomCow", "DATA_TYPE");
        horseIndex = simpleIndex(
                new String[]{"world.entity.animal.equine", "world.entity.animal.horse", "world.entity.animal"},
                "Horse", "DATA_ID_TYPE_VARIANT");
    }

    private int simpleIndex(String[] subPackages, String className, String fieldName) {
        try {
            Class<?> clazz = nmsAny(subPackages, className);
            return Metadata.indexOf(Reflect.staticField(clazz, fieldName));
        } catch (RuntimeException e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "FoliaNPC: " + className + " variant unavailable", e);
            return -1;
        }
    }

    private void resolveHolderVariants() {
        if (identifierClass == null) {
            return;
        }
        Class<?> serializers = Reflect.nms("network.syncher", "EntityDataSerializers", "DataWatcherRegistry");
        Class<?> registries = Reflect.nms("core.registries", "Registries", "Registries");
        try {
            Class<?> cat = nmsAny(new String[]{"world.entity.animal.feline", "world.entity.animal"}, "Cat");
            catIndex = Metadata.indexOf(Reflect.staticField(cat, "DATA_VARIANT_ID"));
            catSerializer = Reflect.staticField(serializers, "CAT_VARIANT");
            catRegistry = registryFor(Reflect.staticField(registries, "CAT_VARIANT"));
        } catch (RuntimeException e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "FoliaNPC: cat variant unavailable", e);
            catIndex = -1;
        }
        try {
            Class<?> wolf = nmsAny(new String[]{"world.entity.animal.wolf", "world.entity.animal"}, "Wolf");
            wolfIndex = Metadata.indexOf(Reflect.staticField(wolf, "DATA_VARIANT_ID"));
            wolfSerializer = Reflect.staticField(serializers, "WOLF_VARIANT");
            wolfRegistry = registryFor(Reflect.staticField(registries, "WOLF_VARIANT"));
        } catch (RuntimeException e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "FoliaNPC: wolf variant unavailable", e);
            wolfIndex = -1;
        }
        try {
            Class<?> frog = nmsAny(new String[]{"world.entity.animal.frog", "world.entity.animal"}, "Frog");
            frogIndex = Metadata.indexOf(Reflect.staticField(frog, "DATA_VARIANT_ID"));
            frogSerializer = Reflect.staticField(serializers, "FROG_VARIANT");
            frogRegistry = registryFor(Reflect.staticField(registries, "FROG_VARIANT"));
        } catch (RuntimeException e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "FoliaNPC: frog variant unavailable", e);
            frogIndex = -1;
        }
    }

    private void resolveVillagerData() {
        if (identifierClass == null) {
            return;
        }
        try {
            String[] villagerPackages = {"world.entity.npc.villager", "world.entity.npc"};
            Class<?> villager = nmsAny(villagerPackages, "Villager");
            Class<?> villagerData = nmsAny(villagerPackages, "VillagerData");
            Class<?> holder = Reflect.nms("core", "Holder", "Holder");
            Class<?> serializers = Reflect.nms("network.syncher", "EntityDataSerializers", "DataWatcherRegistry");
            Class<?> registries = Reflect.nms("core.registries", "Registries", "Registries");

            villagerDataIndex = Metadata.indexOf(Reflect.staticField(villager, "DATA_VILLAGER_DATA"));
            villagerDataSerializer = Reflect.staticField(serializers, "VILLAGER_DATA");
            villagerDataCtor = Reflect.constructor(villagerData, holder, holder, int.class);
            villagerTypeRegistry = registryFor(Reflect.staticField(registries, "VILLAGER_TYPE"));
            villagerProfessionRegistry = registryFor(Reflect.staticField(registries, "VILLAGER_PROFESSION"));
        } catch (RuntimeException e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "FoliaNPC: villager data unavailable", e);
            villagerDataIndex = -1;
        }
    }

    private static Class<?> nmsAny(String[] subPackages, String mojangName) {
        for (String sub : subPackages) {
            Class<?> c = Reflect.tryClass("net.minecraft." + sub + "." + mojangName);
            if (c != null) {
                return c;
            }
        }
        throw new IllegalStateException("Missing NMS class " + mojangName + " in " + Arrays.toString(subPackages));
    }

    Object scalePacket(int entityId, double scale) {
        if (scaleAttribute == null) {
            return null;
        }
        Object snapshot = Reflect.newInstance(scaleSnapshotCtor, scaleAttribute, scale, List.of());
        return Reflect.newInstance(scalePacketCtor, entityId, List.of(snapshot));
    }

    boolean scaleSupported() {
        return scaleAttribute != null;
    }

    private void resolveScale() {
        try {
            Class<?> attributes = Reflect.nms("world.entity.ai.attributes", "Attributes", "GenericAttributes");
            Class<?> packet = Reflect.nms("network.protocol.game",
                    "ClientboundUpdateAttributesPacket", "PacketPlayOutUpdateAttributes");
            Class<?> snapshot = Nms.nested(packet, "AttributeSnapshot", "AttributeSnapshot");
            this.scaleSnapshotCtor = Reflect.constructor(snapshot,
                    Reflect.nms("core", "Holder", "Holder"), double.class, Collection.class);
            this.scalePacketCtor = Reflect.constructor(packet, int.class, List.class);
            this.scaleAttribute = Reflect.staticField(attributes, "SCALE");
        } catch (RuntimeException e) {
            this.scaleAttribute = null;
        }
    }
}
