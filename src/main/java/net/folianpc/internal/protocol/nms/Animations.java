package net.folianpc.internal.protocol.nms;

import java.lang.reflect.Field;

// One-shot animations (swing arm, etc). The packet's only public constructor wants a live Entity we
// do not have, so it is allocated and its two int fields (entity id, action) are set directly.
final class Animations {

    private final Class<?> packetClass;
    private final Field entityIdField;
    private final Field actionField;

    Animations() {
        this.packetClass = Reflect.nms("network.protocol.game",
                "ClientboundAnimatePacket", "PacketPlayOutAnimation");
        Field[] ints = intFields(packetClass);
        this.entityIdField = ints[0];
        this.actionField = ints[1];
    }

    Object packet(int entityId, int action) {
        Object packet = Reflect.allocate(packetClass);
        Reflect.set(entityIdField, packet, entityId);
        Reflect.set(actionField, packet, action);
        return packet;
    }

    // Declared in order: id then action. Grabbing the two int fields positionally avoids depending
    // on obfuscated names.
    private static Field[] intFields(Class<?> type) {
        Field id = null;
        Field action = null;
        for (Field f : type.getDeclaredFields()) {
            if (f.getType() == int.class) {
                f.setAccessible(true);
                if (id == null) {
                    id = f;
                } else if (action == null) {
                    action = f;
                }
            }
        }
        if (id == null || action == null) {
            throw new IllegalStateException("ClientboundAnimatePacket layout changed");
        }
        return new Field[]{id, action};
    }
}
