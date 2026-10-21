package net.folianpc.internal;

import net.folianpc.api.ClickType;
import net.folianpc.api.NpcData;
import net.folianpc.api.event.NpcInteractEvent;
import net.folianpc.internal.scheduler.Schedulers;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ItemUseTest {
    private RecordingProtocolBackend backend;
    private NpcManager manager;
    private PlayerTracker tracker;
    private NpcImpl npc;
    private Player player;
    private AtomicLong clock;

    @BeforeEach
    void setUp() {
        Schedulers.setSynchronousForTesting(true);
        backend = new RecordingProtocolBackend();
        tracker = new PlayerTracker();
        manager = new NpcManager(null, backend, tracker);
        manager.events(event -> {});
        clock = new AtomicLong(1000);
        manager.clock(clock::get);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        tracker.put(new PlayerTracker.Tracked(player, "world", 1, 64, 0));
        npc = manager.create("Shop", new Position("world", 0, 64, 0, 0, 0));
        manager.tick();
    }

    @AfterEach
    void tearDown() {
        Schedulers.setSynchronousForTesting(false);
    }

    private PlayerInteractEvent use(Action action, EquipmentSlot hand) {
        PlayerInteractEvent event = new PlayerInteractEvent(player, action, null, null, BlockFace.SELF, hand);
        manager.onItemUse(event);
        return event;
    }

    @Test
    void optInDeniesBothHandsWithoutStoppingNpcActions() {
        AtomicInteger actions = new AtomicInteger();
        npc.onClick((p, n, click) -> actions.incrementAndGet());
        assertFalse(npc.cancelItemUse());
        backend.fireInteract(player, npc.entityId(), ClickType.RIGHT);
        assertNotEquals(Event.Result.DENY, use(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND).useItemInHand());

        npc.cancelItemUse(true);
        backend.fireInteract(player, npc.entityId(), ClickType.RIGHT);
        assertEquals(Event.Result.DENY, use(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND).useItemInHand());
        assertEquals(Event.Result.DENY, use(Action.RIGHT_CLICK_AIR, EquipmentSlot.OFF_HAND).useItemInHand());
        assertEquals(2, actions.get());
        assertNotEquals(Event.Result.DENY, use(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND).useItemInHand());
    }

    @Test
    void packetObservationWorksWithoutDispatchingNpcCallbacks() {
        npc.cancelItemUse(true);
        AtomicInteger actions = new AtomicInteger();
        npc.onClick((p, n, click) -> actions.incrementAndGet());
        backend.fireRightClickPacket(player, npc.entityId());
        assertEquals(Event.Result.DENY, use(Action.RIGHT_CLICK_BLOCK, EquipmentSlot.OFF_HAND).useItemInHand());
        assertEquals(0, actions.get());
    }

    @Test
    void cooldownAndCancelledNpcEventsDoNotDisableItemProtection() {
        npc.cancelItemUse(true).cooldown(1000);
        AtomicInteger events = new AtomicInteger();
        manager.events(event -> {
            if (event instanceof NpcInteractEvent interact) {
                events.incrementAndGet();
                interact.setCancelled(true);
            }
        });
        backend.fireInteract(player, npc.entityId(), ClickType.RIGHT);
        assertTrue(manager.denyItemUse(player, EquipmentSlot.HAND));
        clock.addAndGet(10);
        backend.fireInteract(player, npc.entityId(), ClickType.RIGHT);
        assertTrue(manager.denyItemUse(player, EquipmentSlot.HAND));
        assertEquals(1, events.get());
    }

    @Test
    void expiresAndDoesNotAffectLeftClicksOrOtherPlayers() {
        npc.cancelItemUse(true);
        backend.fireInteract(player, npc.entityId(), ClickType.LEFT);
        assertFalse(manager.denyItemUse(player, EquipmentSlot.HAND));
        backend.fireRightClickPacket(player, npc.entityId());
        Player other = mock(Player.class);
        when(other.getUniqueId()).thenReturn(UUID.randomUUID());
        assertFalse(manager.denyItemUse(other, EquipmentSlot.HAND));
        assertNotEquals(Event.Result.DENY, use(Action.LEFT_CLICK_AIR, EquipmentSlot.HAND).useItemInHand());
        clock.addAndGet(250);
        assertFalse(manager.denyItemUse(player, EquipmentSlot.HAND));
    }

    @Test
    void requiresVisibleNearbyNpcAndClearsOnOtherEntityClicks() {
        npc.cancelItemUse(true);
        npc.viewers().clear();
        backend.fireRightClickPacket(player, npc.entityId());
        assertFalse(manager.denyItemUse(player, EquipmentSlot.HAND));
        npc.viewers().add(player.getUniqueId());
        tracker.put(new PlayerTracker.Tracked(player, "world", 100, 64, 0));
        backend.fireRightClickPacket(player, npc.entityId());
        assertFalse(manager.denyItemUse(player, EquipmentSlot.HAND));
        tracker.put(new PlayerTracker.Tracked(player, "world", 1, 64, 0));
        backend.fireRightClickPacket(player, npc.entityId());
        backend.fireRightClickPacket(player, Integer.MAX_VALUE);
        assertFalse(manager.denyItemUse(player, EquipmentSlot.HAND));
    }

    @Test
    void disablingRemovingChangingWorldAndDisconnectingClearProtection() {
        npc.cancelItemUse(true);
        backend.fireRightClickPacket(player, npc.entityId());
        npc.cancelItemUse(false);
        assertFalse(manager.denyItemUse(player, EquipmentSlot.HAND));
        npc.cancelItemUse(true);
        backend.fireRightClickPacket(player, npc.entityId());
        tracker.put(new PlayerTracker.Tracked(player, "nether", 1, 64, 0));
        assertFalse(manager.denyItemUse(player, EquipmentSlot.HAND));
        tracker.put(new PlayerTracker.Tracked(player, "world", 1, 64, 0));
        backend.fireRightClickPacket(player, npc.entityId());
        manager.dropPlayer(player.getUniqueId());
        assertFalse(manager.denyItemUse(player, EquipmentSlot.HAND));
        npc.viewers().add(player.getUniqueId());
        backend.fireRightClickPacket(player, npc.entityId());
        npc.remove();
        assertFalse(manager.denyItemUse(player, EquipmentSlot.HAND));
    }

    @Test
    void copiesAndSavesSettingAndOldDataConstructorsDefaultToFalse() {
        npc.cancelItemUse(true);
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        assertTrue(npc.copy(new Location(world, 2, 64, 0)).cancelItemUse());
        NpcData data = npc.data();
        assertTrue(data.cancelItemUse());
        NpcData old = new NpcData(data.id(), data.name(), data.type(), data.world(), data.x(), data.y(),
                data.z(), data.yaw(), data.pitch(), data.lookAtPlayers(), data.skin(), data.mirrorSkin(),
                data.equipment(), data.nametag(), data.appearance(), data.pose(), data.baby(),
                data.showInTabList(), data.mobVariant(), data.owner(), data.nametagStyle());
        assertFalse(old.cancelItemUse());
    }

    @Test
    void preservesBlockCancellationAndHandlesPreCancelledAirEvents() {
        npc.cancelItemUse(true);
        backend.fireRightClickPacket(player, npc.entityId());
        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK,
                null, null, BlockFace.SELF, EquipmentSlot.HAND);
        event.setUseInteractedBlock(Event.Result.ALLOW);
        manager.onItemUse(event);
        assertEquals(Event.Result.DENY, event.useItemInHand());
        assertEquals(Event.Result.ALLOW, event.useInteractedBlock());

        backend.fireRightClickPacket(player, npc.entityId());
        PlayerInteractEvent air = new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR,
                null, null, BlockFace.SELF, EquipmentSlot.HAND);
        air.setCancelled(true);
        manager.onItemUse(air);
        assertEquals(Event.Result.DENY, air.useItemInHand());
    }

    @Test
    void closeClearsProtection() {
        npc.cancelItemUse(true);
        backend.fireRightClickPacket(player, npc.entityId());
        manager.close();
        assertFalse(manager.denyItemUse(player, EquipmentSlot.HAND));
    }
}
