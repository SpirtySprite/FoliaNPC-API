package net.folianpc.internal.protocol;

import net.folianpc.api.ClickType;
import org.bukkit.entity.Player;

import java.util.UUID;

public interface ProtocolBackend {

    int nextEntityId();

    void show(Player viewer, NpcSnapshot npc);

    void hide(Player viewer, NpcSnapshot npc);

    void removeEntities(Player viewer, int[] entityIds);

    void updateMeta(Player viewer, NpcSnapshot npc);

    void refreshHologram(Player viewer, NpcSnapshot npc);

    void animate(Player viewer, int entityId, int action);

    void scale(Player viewer, int entityId, double scale);

    void look(Player viewer, int entityId, float yaw, float pitch);

    void move(Player viewer, int entityId, double dx, double dy, double dz);

    void equip(Player viewer, int entityId,
               java.util.Map<org.bukkit.inventory.EquipmentSlot, org.bukkit.inventory.ItemStack> equipment);

    void injectViewer(Player viewer);

    void ejectViewer(Player viewer);

    void onInteract(InteractSink sink);

    @FunctionalInterface
    interface InteractSink {
        boolean handle(Player viewer, int entityId, ClickType type, boolean sneaking);
    }
}
