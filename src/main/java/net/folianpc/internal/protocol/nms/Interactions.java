package net.folianpc.internal.protocol.nms;

import net.folianpc.api.ClickType;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Decodes inbound interact packets into a click, or null when the packet should be left alone. */
final class Interactions {

    record Click(int entityId, ClickType type, boolean sneaking) {
    }

    private final Class<?> packetClass;
    private final Class<?> handClass;
    private final Field entityIdField;
    private final Field sneakingField;
    private final Field actionField;
    private final Method actionType;

    Interactions() {
        this.packetClass = Reflect.nms("network.protocol.game",
                "ServerboundInteractPacket", "PacketPlayInUseEntity");
        this.handClass = Reflect.nms("world", "InteractionHand", "EnumHand");
        this.entityIdField = Reflect.fieldOfType(packetClass, int.class);
        this.sneakingField = Reflect.fieldOfType(packetClass, boolean.class); // usingSecondaryAction

        // Newer versions dropped getActionType(); read the Action and match its getter by return type.
        Class<?> action = Nms.nested(packetClass, "Action", "b");
        Class<?> type = Nms.nested(packetClass, "ActionType", "c");
        this.actionField = Reflect.fieldOfType(packetClass, action);
        this.actionType = Reflect.methodReturning(action, type);
    }

    boolean isInteract(Object packet) {
        return packetClass.isInstance(packet);
    }

    Click decode(Object packet) {
        Object action = Reflect.get(actionField, packet);
        String type = ((Enum<?>) Reflect.invoke(actionType, action)).name();

        // A right click sends INTERACT_AT then INTERACT, and fires for both hands; count one of each.
        if (type.equals("INTERACT_AT") || "OFF_HAND".equals(hand(action))) {
            return null;
        }
        int entityId = (int) Reflect.get(entityIdField, packet);
        boolean sneaking = (boolean) Reflect.get(sneakingField, packet);
        return new Click(entityId, type.equals("ATTACK") ? ClickType.LEFT : ClickType.RIGHT, sneaking);
    }

    private String hand(Object action) {
        for (Class<?> c = action.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == handClass) {
                    f.setAccessible(true);
                    Object hand = Reflect.get(f, action);
                    return hand == null ? null : ((Enum<?>) hand).name();
                }
            }
        }
        return null;
    }
}
