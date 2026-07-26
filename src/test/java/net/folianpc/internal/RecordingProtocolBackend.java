package net.folianpc.internal;

import net.folianpc.api.ClickType;
import net.folianpc.internal.protocol.NpcSnapshot;
import net.folianpc.internal.protocol.ProtocolBackend;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

// Test double: records every backend call so tests can assert on what the manager decided to send.
final class RecordingProtocolBackend implements ProtocolBackend {

    record Show(Player viewer, NpcSnapshot npc) {
    }

    record Hide(Player viewer, int entityId, UUID uuid) {
    }

    record Look(Player viewer, int entityId, float yaw, float pitch) {
    }

    record Move(Player viewer, int entityId, double dx, double dy, double dz) {
    }

    record Equip(Player viewer, int entityId,
                 java.util.Map<org.bukkit.inventory.EquipmentSlot, org.bukkit.inventory.ItemStack> equipment) {
    }

    final List<Show> shows = new ArrayList<>();
    final List<Hide> hides = new ArrayList<>();
    final List<Look> looks = new ArrayList<>();
    final List<Move> moves = new ArrayList<>();
    final List<Equip> equips = new ArrayList<>();
    final List<int[]> removed = new ArrayList<>();
    final List<NpcSnapshot> metas = new ArrayList<>();
    final List<Double> scales = new ArrayList<>();
    final List<NpcSnapshot> hologramRefreshes = new ArrayList<>();
    final List<Integer> animations = new ArrayList<>();

    private final AtomicInteger ids = new AtomicInteger(1);
    private InteractSink sink;

    @Override
    public int nextEntityId() {
        return ids.getAndIncrement();
    }

    @Override
    public void show(Player viewer, NpcSnapshot npc) {
        shows.add(new Show(viewer, npc));
    }

    @Override
    public void hide(Player viewer, NpcSnapshot npc) {
        hides.add(new Hide(viewer, npc.entityId(), npc.uuid()));
    }

    @Override
    public void look(Player viewer, int entityId, float yaw, float pitch) {
        looks.add(new Look(viewer, entityId, yaw, pitch));
    }

    @Override
    public void move(Player viewer, int entityId, double dx, double dy, double dz) {
        moves.add(new Move(viewer, entityId, dx, dy, dz));
    }

    @Override
    public void equip(Player viewer, int entityId,
                      java.util.Map<org.bukkit.inventory.EquipmentSlot, org.bukkit.inventory.ItemStack> equipment) {
        equips.add(new Equip(viewer, entityId, equipment));
    }

    @Override
    public void removeEntities(Player viewer, int[] entityIds) {
        removed.add(entityIds);
    }

    @Override
    public void updateMeta(Player viewer, NpcSnapshot npc) {
        metas.add(npc);
    }

    @Override
    public void refreshHologram(Player viewer, NpcSnapshot npc) {
        hologramRefreshes.add(npc);
    }

    @Override
    public void animate(Player viewer, int entityId, int action) {
        animations.add(action);
    }

    @Override
    public void scale(Player viewer, int entityId, double scale) {
        scales.add(scale);
    }

    @Override
    public void injectViewer(Player viewer) {
    }

    @Override
    public void ejectViewer(Player viewer) {
    }

    @Override
    public void onInteract(InteractSink sink) {
        this.sink = sink;
    }

    void fireInteract(Player viewer, int entityId, ClickType type) {
        sink.handle(viewer, entityId, type, false);
    }

    void fireInteract(Player viewer, int entityId, ClickType type, boolean sneaking) {
        sink.handle(viewer, entityId, type, sneaking);
    }
}
