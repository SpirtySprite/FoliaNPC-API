package net.folianpc.internal.protocol.nms;

import org.bukkit.Bukkit;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Worn and held items. Works for player and mob NPCs alike, and applies without a respawn.
final class Equipment {

    private final Constructor<?> packetCtor;
    private final Class<?> slotClass;
    private final Method asNmsCopy;
    private final Method pairOf;

    Equipment() {
        Class<?> packet = Reflect.nms("network.protocol.game",
                "ClientboundSetEquipmentPacket", "PacketPlayOutEntityEquipment");
        this.packetCtor = Reflect.constructor(packet, int.class, List.class);
        this.slotClass = Reflect.nms("world.entity", "EquipmentSlot", "EnumItemSlot");
        this.pairOf = Reflect.method(Reflect.tryClass("com.mojang.datafixers.util.Pair"),
                "of", Object.class, Object.class);

        // CraftBukkit's package is versioned on some builds and not others; derive it from the server.
        String craftBase = Bukkit.getServer().getClass().getPackage().getName();
        Class<?> craftItem = Reflect.tryClass(craftBase + ".inventory.CraftItemStack");
        if (craftItem == null) {
            craftItem = Reflect.tryClass("org.bukkit.craftbukkit.inventory.CraftItemStack");
        }
        this.asNmsCopy = Reflect.method(craftItem, "asNMSCopy", ItemStack.class);
    }

    Object packet(int entityId, Map<EquipmentSlot, ItemStack> equipment) {
        List<Object> pairs = new ArrayList<>(equipment.size());
        equipment.forEach((slot, item) -> {
            Object nmsSlot = slot(slot);
            if (nmsSlot != null) {
                pairs.add(Reflect.invoke(pairOf, null, nmsSlot, Reflect.invoke(asNmsCopy, null, item)));
            }
        });
        return pairs.isEmpty() ? null : Reflect.newInstance(packetCtor, entityId, pairs);
    }

    private Object slot(EquipmentSlot slot) {
        String name = switch (slot) {
            case HAND -> "MAINHAND";
            case OFF_HAND -> "OFFHAND";
            default -> slot.name();
        };
        try {
            return Reflect.enumConstant(slotClass, name);
        } catch (RuntimeException e) {
            return null; // slot this version doesn't have, e.g. BODY on older builds
        }
    }
}
