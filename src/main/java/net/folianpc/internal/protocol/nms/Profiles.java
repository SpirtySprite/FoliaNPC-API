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

    // GameProfile/PropertyMap dropped their multi-arg constructors at some point (present on 1.21.11,
    // gone on 1.21.1's authlib build) - building via the 2-arg ctor and mutating the profile's own
    // PropertyMap through getProperties() works on both, since that map has always been a live Multimap.
    private Object profile(NpcSnapshot npc, String mirrorValue, String mirrorSignature) {
        Object profile = Reflect.newInstance(profileCtor, npc.uuid(), npc.profileName());
        String value = mirrorValue != null ? mirrorValue : npc.skinValue();
        String signature = mirrorValue != null ? mirrorSignature : npc.skinSignature();
        if (value != null && propertyCtor != null) {
            Object property = Reflect.newInstance(propertyCtor, "textures", value, signature);
            Object properties = Reflect.invoke(propertiesGetter, profile);
            Reflect.invoke(propertiesPut, properties, "textures", property);
        }
        return profile;
    }

    boolean skinsSupported() {
        return propertyCtor != null;
    }

    private void resolveSkins() {
        try {
            Class<?> property = Reflect.tryClass("com.mojang.authlib.properties.Property");
            Class<?> multimap = Reflect.tryClass("com.google.common.collect.Multimap");
            this.propertyCtor = Reflect.constructor(property, String.class, String.class, String.class);
            this.propertiesGetter = Reflect.method(profileClass, "getProperties");
            this.propertiesPut = multimap.getMethod("put", Object.class, Object.class);
        } catch (ReflectiveOperationException | RuntimeException e) {
            this.propertyCtor = null;
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
