package net.folianpc.internal.protocol.nms;

import net.folianpc.internal.protocol.NpcSnapshot;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

final class Profiles {

    private final Class<?> profileClass;
    private final Class<?> gameTypeClass;
    private final Class<?> actionClass;
    private final Constructor<?> profileCtor;
    private final Constructor<?> updateCtor;
    private final Constructor<?> removeCtor;
    private final Constructor<?> entryCtor;
    private final RecordComponent[] entryComponents;
    private final Field entriesField;
    private final Object survival;
    private final Object addPlayer;
    private final Object updateListed;

    private Constructor<?> propertyCtor;

    private Method propertiesGetter;
    private Method propertiesPut;

    private Constructor<?> profileCtor3;
    private Constructor<?> propertyMapCtor;
    private Method multimapOfOne;

    Profiles() {
        Class<?> update = Reflect.nms("network.protocol.game",
                "ClientboundPlayerInfoUpdatePacket", "ClientboundPlayerInfoUpdatePacket");
        Class<?> remove = Reflect.nms("network.protocol.game",
                "ClientboundPlayerInfoRemovePacket", "ClientboundPlayerInfoRemovePacket");
        Class<?> entryClass = Nms.nested(update, "Entry", "b");

        this.profileClass = Reflect.tryClass("com.mojang.authlib.GameProfile");
        this.gameTypeClass = Reflect.nms("world.level", "GameType", "EnumGamemode");
        this.actionClass = Nms.nested(update, "Action", "a");
        this.profileCtor = Reflect.constructor(profileClass, UUID.class, String.class);
        this.updateCtor = Reflect.constructor(update, EnumSet.class, Collection.class);
        this.removeCtor = Reflect.constructor(remove, List.class);
        this.survival = Reflect.enumConstant(gameTypeClass, "SURVIVAL");
        this.addPlayer = Reflect.enumConstant(actionClass, "ADD_PLAYER");
        this.updateListed = Reflect.enumConstant(actionClass, "UPDATE_LISTED");

        this.entryComponents = entryClass.getRecordComponents();
        this.entryCtor = Reflect.constructor(entryClass, Arrays.stream(entryComponents)
                .map(RecordComponent::getType).toArray(Class<?>[]::new));
        this.entriesField = entriesField(update);
        resolveSkins();
    }

    Object addPacket(NpcSnapshot npc) {
        return addPacket(npc, null, null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    Object addPacket(NpcSnapshot npc, String mirrorValue, String mirrorSignature) {
        EnumSet actions = EnumSet.noneOf((Class) actionClass);
        actions.add(addPlayer);
        actions.add(updateListed);
        Object packet = Reflect.newInstance(updateCtor, actions, List.of());
        Reflect.set(entriesField, packet, List.of(entry(npc, mirrorValue, mirrorSignature)));
        return packet;
    }

    Object removePacket(UUID npcId) {
        return Reflect.newInstance(removeCtor, List.of(npcId));
    }

    private Object entry(NpcSnapshot npc, String mirrorValue, String mirrorSignature) {
        Object profile = profile(npc, mirrorValue, mirrorSignature);
        Object[] args = new Object[entryComponents.length];
        for (int i = 0; i < args.length; i++) {
            Class<?> type = entryComponents[i].getType();
            if (type == UUID.class) {
                args[i] = npc.uuid();
            } else if (type == profileClass) {
                args[i] = profile;
            } else if (type == boolean.class) {
                args[i] = "listed".equals(entryComponents[i].getName()) ? npc.showInTabList() : true;
            } else if (type == gameTypeClass) {
                args[i] = survival;
            } else if (type == int.class) {
                args[i] = 0;
            } else {
                args[i] = null;
            }
        }
        return Reflect.newInstance(entryCtor, args);
    }

    private Object profile(NpcSnapshot npc, String mirrorValue, String mirrorSignature) {
        String value = mirrorValue != null ? mirrorValue : npc.skinValue();
        String signature = mirrorValue != null ? mirrorSignature : npc.skinSignature();

        if (value != null && propertyCtor != null && profileCtor3 != null) {
            Object property = Reflect.newInstance(propertyCtor, "textures", value, signature);
            Object multimap = Reflect.invoke(multimapOfOne, null, "textures", property);
            Object properties = Reflect.newInstance(propertyMapCtor, multimap);
            return Reflect.newInstance(profileCtor3, npc.uuid(), npc.profileName(), properties);
        }

        Object profile = Reflect.newInstance(profileCtor, npc.uuid(), npc.profileName());
        if (value != null && propertyCtor != null) {
            Object property = Reflect.newInstance(propertyCtor, "textures", value, signature);
            Object properties = Reflect.invoke(propertiesGetter, profile);
            Reflect.invoke(propertiesPut, properties, "textures", property);
        }
        return profile;
    }

    boolean skinsSupported() {
        return propertyCtor != null && (profileCtor3 != null || propertiesGetter != null);
    }

    private void resolveSkins() {
        try {
            Class<?> property = Reflect.tryClass("com.mojang.authlib.properties.Property");
            this.propertyCtor = Reflect.constructor(property, String.class, String.class, String.class);
        } catch (RuntimeException e) {
            this.propertyCtor = null;
            return;
        }
        resolveImmutablePropertyMapPath();
        if (profileCtor3 == null) {
            resolveMutablePropertyMapPath();
        }
    }

    private void resolveImmutablePropertyMapPath() {
        try {
            Class<?> propertyMap = Reflect.tryClass("com.mojang.authlib.properties.PropertyMap");
            Class<?> multimap = Reflect.tryClass("com.google.common.collect.Multimap");
            Class<?> immutableListMultimap = Reflect.tryClass("com.google.common.collect.ImmutableListMultimap");
            this.propertyMapCtor = Reflect.constructor(propertyMap, multimap);
            this.multimapOfOne = Reflect.method(immutableListMultimap, "of", Object.class, Object.class);
            this.profileCtor3 = Reflect.constructor(profileClass, UUID.class, String.class, propertyMap);
        } catch (RuntimeException e) {
            this.profileCtor3 = null;
        }
    }

    private void resolveMutablePropertyMapPath() {
        try {
            Class<?> multimap = Reflect.tryClass("com.google.common.collect.Multimap");
            // GameProfile.getProperties() was renamed to properties() at some point.
            Method propertiesGetterMethod;
            try {
                propertiesGetterMethod = Reflect.method(profileClass, "getProperties");
            } catch (RuntimeException renamed) {
                propertiesGetterMethod = Reflect.method(profileClass, "properties");
            }
            this.propertiesGetter = propertiesGetterMethod;
            this.propertiesPut = multimap.getMethod("put", Object.class, Object.class);
        } catch (ReflectiveOperationException | RuntimeException e) {
            this.propertiesGetter = null;
        }
    }

    private static Field entriesField(Class<?> update) {
        try {
            return Reflect.field(update, "entries");
        } catch (RuntimeException e) {
            return Reflect.fieldOfType(update, List.class);
        }
    }
}
