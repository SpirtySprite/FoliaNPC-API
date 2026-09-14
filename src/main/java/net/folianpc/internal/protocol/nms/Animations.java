package net.folianpc.internal.protocol.nms;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

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

    private static Field[] intFields(Class<?> type) {
        Field id = null;
        Field action = null;
        for (Field f : type.getDeclaredFields()) {
            if (f.getType() == int.class && !Modifier.isStatic(f.getModifiers())) {
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
