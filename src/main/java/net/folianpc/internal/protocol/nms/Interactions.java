package net.folianpc.internal.protocol.nms;

import net.folianpc.api.ClickType;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class Interactions {

    record Click(int entityId, ClickType type, boolean sneaking) {
    }

    private final Class<?> interactClass;
    private final Class<?> handClass;
    private final Field entityIdField;
    private final Field sneakingField;

    private final Field actionField;
    private final Method actionTypeMethod;

    private final Class<?> attackClass;
    private final Field attackEntityIdField;

    Interactions() {
        this.interactClass = Reflect.nms("network.protocol.game",
                "ServerboundInteractPacket", "PacketPlayInUseEntity");
        this.handClass = Reflect.nms("world", "InteractionHand", "EnumHand");
        this.entityIdField = Reflect.fieldOfType(interactClass, int.class);
        this.sneakingField = Reflect.fieldOfType(interactClass, boolean.class); // usingSecondaryAction

        Field action = null;
        Method actionType = null;
        Class<?> attack = null;
        Field attackId = null;
        try {
            // Newer versions dropped getActionType(); read the Action and match its getter by return type.
            Class<?> actionClass = Nms.nested(interactClass, "Action", "b");
            Class<?> actionTypeClass = Nms.nested(interactClass, "ActionType", "c");
            action = Reflect.fieldOfType(interactClass, actionClass);
            actionType = Reflect.methodReturning(actionClass, actionTypeClass);
        } catch (RuntimeException splitDesign) {
            attack = Reflect.nms("network.protocol.game", "ServerboundAttackPacket", null);
            attackId = Reflect.fieldOfType(attack, int.class);
        }
        this.actionField = action;
        this.actionTypeMethod = actionType;
        this.attackClass = attack;
        this.attackEntityIdField = attackId;
    }

    boolean isInteract(Object packet) {
        return interactClass.isInstance(packet) || (attackClass != null && attackClass.isInstance(packet));
    }

    Click decode(Player viewer, Object packet) {
        if (attackClass != null && attackClass.isInstance(packet)) {
            int entityId = (int) Reflect.get(attackEntityIdField, packet);
            return new Click(entityId, ClickType.LEFT, viewer.isSneaking());
        }

        if (actionField != null) {
            Object action = Reflect.get(actionField, packet);
            String type = ((Enum<?>) Reflect.invoke(actionTypeMethod, action)).name();

            // A right click sends INTERACT_AT then INTERACT, and fires for both hands; count one of each.
            if (type.equals("INTERACT_AT") || "OFF_HAND".equals(hand(action))) {
                return null;
            }
            int entityId = (int) Reflect.get(entityIdField, packet);
            boolean sneaking = (boolean) Reflect.get(sneakingField, packet);
            return new Click(entityId, type.equals("ATTACK") ? ClickType.LEFT : ClickType.RIGHT, sneaking);
        }

        if ("OFF_HAND".equals(hand(packet))) {
            return null;
        }
        int entityId = (int) Reflect.get(entityIdField, packet);
        boolean sneaking = (boolean) Reflect.get(sneakingField, packet);
        return new Click(entityId, ClickType.RIGHT, sneaking);
    }

    private String hand(Object holder) {
        for (Class<?> c = holder.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == handClass) {
                    f.setAccessible(true);
                    Object hand = Reflect.get(f, holder);
                    return hand == null ? null : ((Enum<?>) hand).name();
                }
            }
        }
        return null;
    }
}
