package net.folianpc.internal.protocol.nms;

import java.lang.reflect.Constructor;
import java.util.List;

final class Metadata {

    private final Constructor<?> valueCtor;
    private final Constructor<?> packetCtor;
    final Object byteSerializer;
    final Object intSerializer;
    final Object booleanSerializer;
    final Object floatSerializer;

    Metadata() {
        Class<?> serializers = Reflect.nms("network.syncher", "EntityDataSerializers", "DataWatcherRegistry");
        Class<?> serializer = Reflect.nms("network.syncher", "EntityDataSerializer", "DataWatcherSerializer");
        Class<?> synched = Reflect.nms("network.syncher", "SynchedEntityData", "DataWatcher");
        Class<?> dataValue = Nms.nested(synched, "DataValue", "b");
        Class<?> setEntityData = Reflect.nms("network.protocol.game",
                "ClientboundSetEntityDataPacket", "PacketPlayOutEntityMetadata");

        this.valueCtor = Reflect.constructor(dataValue, int.class, serializer, Object.class);
        this.packetCtor = Reflect.constructor(setEntityData, int.class, List.class);
        this.byteSerializer = Reflect.staticField(serializers, "BYTE");
        this.intSerializer = optional(serializers, "INT");
        this.booleanSerializer = optional(serializers, "BOOLEAN");
        this.floatSerializer = optional(serializers, "FLOAT");
    }

    private static Object optional(Class<?> serializers, String name) {
        try {
            return Reflect.staticField(serializers, name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    Object value(int index, Object serializer, Object data) {
        return Reflect.newInstance(valueCtor, index, serializer, data);
    }

    Object packet(int entityId, List<Object> values) {
        return Reflect.newInstance(packetCtor, entityId, values);
    }

    static int indexOf(Object accessor) {
        return (int) Reflect.invoke(Reflect.method(accessor.getClass(), "id"), accessor);
    }
}
