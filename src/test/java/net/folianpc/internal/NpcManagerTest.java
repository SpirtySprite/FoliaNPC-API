package net.folianpc.internal;

import net.folianpc.api.ClickType;
import net.folianpc.api.Npc;
import net.folianpc.api.NpcAction;
import net.folianpc.api.NpcClickContext;
import net.folianpc.api.NpcAppearance;
import net.folianpc.api.NpcData;
import net.folianpc.api.NpcPose;
import net.folianpc.api.event.NpcInteractEvent;
import net.folianpc.api.event.NpcRemoveEvent;
import net.folianpc.api.event.NpcSpawnEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.Event;
import net.folianpc.api.Skin;
import net.folianpc.internal.scheduler.Schedulers;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NpcManagerTest {

    private RecordingProtocolBackend backend;
    private PlayerTracker tracker;
    private NpcManager manager;
    private List<Event> events;

    @BeforeEach
    void setUp() {
        Schedulers.setSynchronousForTesting(true);
        backend = new RecordingProtocolBackend();
        tracker = new PlayerTracker();
        events = new ArrayList<>();
        manager = new NpcManager(mock(Plugin.class), backend, tracker);
        manager.events(events::add);
    }

    @SuppressWarnings("unchecked")
    private <T extends Event> T firstEvent(Class<T> type) {
        return (T) events.stream().filter(type::isInstance).findFirst().orElse(null);
    }

    @AfterEach
    void tearDown() {
        Schedulers.setSynchronousForTesting(false);
    }

    private Player player(UUID id) {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(id);
        return p;
    }

    private void track(Player p, String world, double x, double y, double z) {
        tracker.put(new PlayerTracker.Tracked(p, world, x, y, z));
    }

    @Test
    void showsPlayerInRange() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        UUID id = UUID.randomUUID();
        Player p = player(id);
        track(p, "world", 0, 64, 10);

        manager.tick();

        assertEquals(1, backend.shows.size());
        assertSame(p, backend.shows.get(0).viewer());
        assertEquals("Bob", backend.shows.get(0).npc().name());
        assertTrue(npc.viewers().contains(id));
    }

    @Test
    void doesNotShowTwice() {
        manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        Player p = player(UUID.randomUUID());
        track(p, "world", 0, 64, 5);

        manager.tick();
        manager.tick();

        assertEquals(1, backend.shows.size());
    }

    @Test
    void doesNotShowAcrossWorlds() {
        manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        Player p = player(UUID.randomUUID());
        track(p, "nether", 0, 64, 1);

        manager.tick();

        assertTrue(backend.shows.isEmpty());
    }

    @Test
    void doesNotShowBeyondViewDistance() {
        manager.viewDistance(48);
        manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        Player p = player(UUID.randomUUID());
        track(p, "world", 0, 64, 200);

        manager.tick();

        assertTrue(backend.shows.isEmpty());
    }

    @Test
    void hidesWhenPlayerLeavesRange() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        UUID id = UUID.randomUUID();
        Player p = player(id);
        track(p, "world", 0, 64, 5);
        manager.tick();
        assertEquals(1, backend.shows.size());

        track(p, "world", 0, 64, 500); // walked away
        manager.tick();

        assertEquals(1, backend.hides.size());
        assertFalse(npc.viewers().contains(id));
    }

    @Test
    void lookAtEmitsRotationTowardViewer() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        npc.lookAtPlayers(true);
        Player p = player(UUID.randomUUID());
        track(p, "world", 0, 64, 10); // due south

        manager.tick();

        assertFalse(backend.looks.isEmpty());
        RecordingProtocolBackend.Look look = backend.looks.get(backend.looks.size() - 1);
        assertEquals(npc.entityId(), look.entityId());
        assertEquals(0f, look.yaw(), 0.01f); // facing +Z is yaw 0
    }

    @Test
    void lookIsOnlySentWhenTheRotationActuallyChanges() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        npc.lookAtPlayers(true);
        Player p = player(UUID.randomUUID());
        track(p, "world", 0, 64, 10);

        manager.tick();
        assertEquals(1, backend.looks.size());

        manager.tick();
        manager.tick();

        assertEquals(1, backend.looks.size(), "a still player must not cost packets every pass");

        track(p, "world", 10, 64, 0); // walked around the NPC
        manager.tick();

        assertEquals(2, backend.looks.size());
    }

    @Test
    void despawningClearsTheCachedRotationSoItIsResentOnReturn() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        npc.lookAtPlayers(true);
        UUID id = UUID.randomUUID();
        Player p = player(id);
        track(p, "world", 0, 64, 10);
        manager.tick();
        assertEquals(1, backend.looks.size());

        track(p, "world", 0, 64, 900); // out of range, despawns
        manager.tick();
        track(p, "world", 0, 64, 10);  // back to the exact same rotation
        manager.tick();

        assertEquals(2, backend.looks.size(), "the client reset on despawn, so it must be re-sent");
    }

    @Test
    void forgettingAPlayerMakesTheNextPassRespawnTheNpc() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        UUID id = UUID.randomUUID();
        Player p = player(id);
        track(p, "world", 0, 64, 5);
        manager.tick();
        assertEquals(1, backend.shows.size());

        manager.forgetPlayer(id); // what a respawn does: the client wiped its entities
        manager.tick();

        assertEquals(2, backend.shows.size(), "the NPC must be sent again after a respawn");
    }

    @Test
    void noLookWhenDisabled() {
        manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        Player p = player(UUID.randomUUID());
        track(p, "world", 0, 64, 10);

        manager.tick();

        assertTrue(backend.looks.isEmpty());
    }

    @Test
    void walkingFacesTravelDirectionEvenWithLookAtPlayersOn() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        npc.lookAtPlayers(true);
        Player p = player(UUID.randomUUID());
        track(p, "world", -10, 64, 0); // player is due west; travel is due east (yaw 90)
        manager.tick();
        backend.looks.clear();

        npc.walkToward(10, 64, 0, 4.0); // walking due east
        manager.tick();

        assertFalse(backend.looks.isEmpty(), "a step must send a look packet");
        RecordingProtocolBackend.Look look = backend.looks.get(backend.looks.size() - 1);
        assertEquals(-90f, look.yaw(), 0.5f, "must face the travel direction (east), not the player behind it");
    }

    @Test
    void lookAtPlayersResumesTheInstantItStopsMoving() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        npc.lookAtPlayers(true);
        Player p = player(UUID.randomUUID());
        track(p, "world", 0, 64, 10); // due south of the arrival point too
        npc.walkToward(0.2, 64, 0, 4.0); // arrives within a single step, just barely off the z-axis
        manager.tick();

        assertFalse(npc.moving(), "must have arrived");
        RecordingProtocolBackend.Look look = backend.looks.get(backend.looks.size() - 1);
        assertEquals(0f, look.yaw(), 2.0f, "must face the player now that it has stopped");
    }

    @Test
    void clickRoutesToListener() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        AtomicReference<Npc> clicked = new AtomicReference<>();
        AtomicReference<ClickType> clickType = new AtomicReference<>();
        Player p = player(UUID.randomUUID());
        npc.onClick((who, n, type) -> {
            clicked.set(n);
            clickType.set(type);
        });

        backend.fireInteract(p, npc.entityId(), ClickType.RIGHT);

        assertSame(npc, clicked.get());
        assertEquals(ClickType.RIGHT, clickType.get());
    }

    @Test
    void removeHidesFromViewersAndUnregisters() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        UUID npcId = npc.id();
        Player p = player(UUID.randomUUID());
        track(p, "world", 0, 64, 5);
        manager.tick();

        npc.remove();

        assertEquals(1, backend.hides.size());
        assertTrue(npc.removed());
        assertNull(manager.get(npcId));
    }

    @Test
    void recreatingFromSavedDataKeepsTheSameId() {
        UUID saved = UUID.randomUUID();

        NpcImpl npc = manager.create(saved, "Bob", EntityType.PLAYER,
                new Position("world", 1, 64, 2, 30, 10));

        assertEquals(saved, npc.id());
        assertSame(npc, manager.get(saved));
    }

    @Test
    void nametagLinesStackAboveTheNpcWithFirstLineOnTop() {
        NpcImpl npc = manager.create("Bob", new Position("world", 5, 64, 7, 0, 0));

        npc.nametag(List.of("top", "bottom"));

        var lines = npc.snapshot().hologram();
        assertEquals(2, lines.size());
        assertEquals("top", lines.get(0).text());
        assertEquals("bottom", lines.get(1).text());
        assertTrue(lines.get(0).y() > lines.get(1).y(), "first line sits highest");
        assertTrue(lines.get(1).y() > 64, "lines float above the NPC's feet");
        assertEquals(5, lines.get(0).x());
        assertEquals(7, lines.get(0).z());
    }

    @Test
    void nametagLinesGetDistinctEntityIdsSeparateFromTheNpc() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));

        npc.nametag(List.of("a", "b"));

        var lines = npc.snapshot().hologram();
        assertNotEquals(lines.get(0).entityId(), lines.get(1).entityId());
        assertNotEquals(npc.entityId(), lines.get(0).entityId());
    }

    @Test
    void changingTheNametagResendsTheNpcToViewers() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        Player p = player(UUID.randomUUID());
        track(p, "world", 0, 64, 5);
        manager.tick();
        int showsBefore = backend.shows.size();

        npc.nametag(List.of("hello"));

        assertEquals(showsBefore + 1, backend.shows.size(), "the profile changed, so it is re-sent");
        var resent = backend.shows.get(backend.shows.size() - 1).npc();
        assertEquals("hello", resent.hologram().get(0).text());
        assertFalse(resent.nametagVisible());
    }

    @Test
    void hidingAlsoRemovesTheNametagEntities() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        npc.nametag(List.of("a", "b"));
        Player p = player(UUID.randomUUID());
        track(p, "world", 0, 64, 5);
        manager.tick();

        track(p, "world", 0, 64, 900); // walked away
        manager.tick();

        assertEquals(1, backend.removed.size());
        assertEquals(2, backend.removed.get(0).length);
    }

    @Test
    void settingANametagHidesTheVanillaNamePlate() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        assertTrue(npc.nametagVisible());

        npc.nametag(List.of("line"));

        assertFalse(npc.nametagVisible());
        assertFalse(npc.snapshot().nametagVisible());
    }

    @Test
    void visibleNamePlateUsesTheDisplayName() {
        NpcImpl npc = manager.create("Steve", new Position("world", 0, 64, 0, 0, 0));

        assertTrue(npc.nametagVisible());
        assertEquals("Steve", npc.profileName(), "a visible plate must read as the display name");
    }

    @Test
    void hiddenNamePlateUsesAUniqueNameSoTeamHidingCannotHitRealPlayers() {
        NpcImpl a = manager.create("Steve", new Position("world", 0, 64, 0, 0, 0));
        NpcImpl b = manager.create("Steve", new Position("world", 1, 64, 0, 0, 0));

        a.nametagVisible(false);
        b.nametagVisible(false);

        assertNotEquals("Steve", a.profileName(), "must not team a real player's name");
        assertNotEquals(a.profileName(), b.profileName());
        assertTrue(a.profileName().length() <= 16, "profile names are limited to 16 chars");
    }

    @Test
    void longDisplayNamesAreTruncatedToAValidProfileName() {
        NpcImpl npc = manager.create("AVeryLongNpcNameIndeed", new Position("world", 0, 64, 0, 0, 0));

        assertEquals(16, npc.profileName().length());
    }

    @Test
    void clearingTheNametagRestoresTheVanillaNamePlate() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        npc.nametag(List.of("line"));

        npc.nametag(List.of());

        assertTrue(npc.nametagVisible());
    }

    @Test
    void clearingTheNametagLeavesNoLines() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        npc.nametag(List.of("a"));

        npc.nametag(List.of());

        assertTrue(npc.snapshot().hologram().isEmpty());
        assertTrue(npc.nametag().isEmpty());
    }

    @Test
    void flagChangesPushMetadataToViewersWithoutRespawning() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        Player p = player(UUID.randomUUID());
        track(p, "world", 0, 64, 5);
        manager.tick();
        int showsBefore = backend.shows.size();

        npc.glowing(true);

        assertEquals(1, backend.metas.size());
        assertTrue(backend.metas.get(0).glowing());
        assertEquals(showsBefore, backend.shows.size(), "flags must not force a respawn");
    }

    @Test
    void flagsDefaultSensiblyAndReachTheSnapshot() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));

        assertFalse(npc.glowing());
        assertFalse(npc.invisible());
        assertTrue(npc.skinLayers(), "skin layers are on by default");
        assertEquals(1.0, npc.scale());

        npc.invisible(true).skinLayers(false);

        assertTrue(npc.snapshot().invisible());
        assertFalse(npc.snapshot().skinLayers());
    }

    @Test
    void scaleIsPushedAndClampedAboveZero() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        Player p = player(UUID.randomUUID());
        track(p, "world", 0, 64, 5);
        manager.tick();

        npc.scale(2.5);

        assertEquals(List.of(2.5), backend.scales);

        npc.scale(-4);

        assertTrue(npc.scale() > 0, "scale must stay positive");
    }

    @Test
    void spawningAndRemovingFireEvents() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));

        assertNotNull(firstEvent(NpcSpawnEvent.class));
        assertSame(npc, firstEvent(NpcSpawnEvent.class).getNpc());

        npc.remove();

        assertNotNull(firstEvent(NpcRemoveEvent.class));
        assertSame(npc, firstEvent(NpcRemoveEvent.class).getNpc());
    }

    @Test
    void interactFiresAnEventCarryingTheClick() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        Player p = player(UUID.randomUUID());

        backend.fireInteract(p, npc.entityId(), ClickType.RIGHT);

        NpcInteractEvent event = firstEvent(NpcInteractEvent.class);
        assertNotNull(event);
        assertSame(npc, event.getNpc());
        assertSame(p, event.getPlayer());
        assertEquals(ClickType.RIGHT, event.getClick());
    }

    @Test
    void cancellingTheInteractEventStopsListenerAndActions() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        Player p = player(UUID.randomUUID());
        List<String> ran = new ArrayList<>();
        npc.onClick((who, clicked, type) -> ran.add("listener"));
        npc.addAction(ClickType.RIGHT, ctx -> ran.add("action"));
        manager.events(event -> {
            events.add(event);
            if (event instanceof NpcInteractEvent interact) {
                interact.setCancelled(true);
            }
        });

        backend.fireInteract(p, npc.entityId(), ClickType.RIGHT);

        assertTrue(ran.isEmpty(), "a cancelled interaction must run nothing");
    }

    @Test
    void aThrowingClickListenerDoesNotStopActions() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        Player p = player(UUID.randomUUID());
        List<String> ran = new ArrayList<>();
        npc.onClick((who, clicked, type) -> {
            throw new IllegalStateException("consumer bug");
        });
        npc.addAction(ClickType.RIGHT, ctx -> ran.add("action"));

        backend.fireInteract(p, npc.entityId(), ClickType.RIGHT);

        assertEquals(List.of("action"), ran);
    }

    @Test
    void glowColourAndCollisionForceTheUniqueWireName() {
        NpcImpl npc = manager.create("Steve", new Position("world", 0, 64, 0, 0, 0));
        assertEquals("Steve", npc.profileName());

        npc.glowColor(NamedTextColor.RED);

        assertNotEquals("Steve", npc.profileName(), "a team must never match a real player's name");
        assertTrue(npc.snapshot().needsTeam());

        npc.glowColor(null).collidable(false);

        assertNotEquals("Steve", npc.profileName());
    }

    @Test
    void aPlainNpcNeedsNoTeamAtAll() {
        NpcImpl npc = manager.create("Steve", new Position("world", 0, 64, 0, 0, 0));

        assertFalse(npc.snapshot().needsTeam());
        assertTrue(npc.collidable());
        assertNull(npc.glowColor());
    }

    @Test
    void appearanceRoundTripsThroughData() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        npc.glowing(true).scale(2.0).glowColor(NamedTextColor.AQUA).collidable(false);

        NpcAppearance saved = npc.data().appearance();
        NpcImpl restored = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        restored.appearance(saved);

        assertTrue(restored.glowing());
        assertEquals(2.0, restored.scale());
        assertEquals(NamedTextColor.AQUA, restored.glowColor());
        assertFalse(restored.collidable());
    }

    @Test
    void hiddenPlayersNeverSeeTheNpcEvenPointBlank() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        UUID id = UUID.randomUUID();
        Player p = player(id);
        track(p, "world", 0, 64, 1);

        npc.hideFrom(id);
        manager.tick();

        assertTrue(backend.shows.isEmpty());
    }

    @Test
    void hidingAfterTheFactDespawnsForThatPlayerOnly() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        UUID hidden = UUID.randomUUID();
        Player a = player(hidden);
        Player b = player(UUID.randomUUID());
        track(a, "world", 0, 64, 5);
        track(b, "world", 0, 64, 5);
        manager.tick();
        assertEquals(2, backend.shows.size());

        npc.hideFrom(hidden);
        manager.tick();

        assertEquals(1, backend.hides.size());
        assertSame(a, backend.hides.get(0).viewer());
        assertFalse(npc.viewers().contains(hidden));
    }

    @Test
    void forcedPlayersSeeTheNpcBeyondViewDistance() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        UUID id = UUID.randomUUID();
        Player p = player(id);
        track(p, "world", 0, 64, 5000);

        npc.showTo(id);
        manager.tick();

        assertEquals(1, backend.shows.size());
    }

    @Test
    void forcingDoesNotCrossWorlds() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        UUID id = UUID.randomUUID();
        Player p = player(id);
        track(p, "nether", 0, 64, 1);

        npc.showTo(id);
        manager.tick();

        assertTrue(backend.shows.isEmpty());
    }

    @Test
    void resettingVisibilityRestoresTheDistanceCheck() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        UUID id = UUID.randomUUID();
        Player p = player(id);
        track(p, "world", 0, 64, 5);
        npc.hideFrom(id);
        manager.tick();
        assertTrue(backend.shows.isEmpty());

        npc.resetVisibility(id);
        manager.tick();

        assertEquals(1, backend.shows.size());
    }

    @Test
    void teleportInSameWorldResendsToViewersInRange() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        Player p = player(UUID.randomUUID());
        track(p, "world", 0, 64, 5);
        manager.tick();
        int showsBefore = backend.shows.size();

        npc.teleportTo(new Position("world", 3, 64, 3, 0, 0));

        assertEquals(3, npc.x());
        assertEquals(showsBefore + 1, backend.shows.size(), "the NPC is re-sent at the new spot");
    }

    @Test
    void teleportToAnotherWorldDespawnsForOldViewers() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        UUID id = UUID.randomUUID();
        Player p = player(id);
        track(p, "world", 0, 64, 5);
        manager.tick();

        npc.teleportTo(new Position("nether", 0, 64, 0, 0, 0));

        assertEquals(1, backend.hides.size());
        assertFalse(npc.viewers().contains(id), "a cross-world teleport drops old viewers");
    }

    @Test
    void walkingAdvancesThePositionAndSlidesForViewers() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        Player p = player(UUID.randomUUID());
        track(p, "world", 0, 64, 5);
        manager.tick();

        npc.walkToward(10, 64, 0, 4.0); // 4 blocks/s -> 0.4 per pass
        manager.tick();

        assertTrue(npc.moving());
        assertEquals(0.4, npc.x(), 1e-6, "moved one step toward the target");
        assertFalse(backend.moves.isEmpty());
        assertEquals(npc.entityId(), backend.moves.get(0).entityId());
    }

    @Test
    void walkingStopsOnArrival() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        Player p = player(UUID.randomUUID());
        track(p, "world", 0, 64, 5);
        manager.tick();

        npc.walkToward(0.2, 64, 0, 4.0); // within a single 0.4 step
        manager.tick();

        assertFalse(npc.moving(), "arrived, so no longer walking");
        assertEquals(0.2, npc.x(), 1e-6);
    }

    // A flat world: solid floor at y<=0, open air above, so y=1 is standable everywhere.
    private World flatWorld(String name) {
        World world = mock(World.class);
        when(world.getName()).thenReturn(name);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        Block solid = mock(Block.class);
        when(solid.isSolid()).thenReturn(true);
        Block air = mock(Block.class);
        when(air.isSolid()).thenReturn(false);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(inv ->
                ((int) inv.getArgument(1)) <= 0 ? solid : air);
        return world;
    }

    // Solid everywhere: no block is ever standable, so no route can exist.
    private World sealedWorld(String name) {
        World world = mock(World.class);
        when(world.getName()).thenReturn(name);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        Block solid = mock(Block.class);
        when(solid.isSolid()).thenReturn(true);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(solid);
        return world;
    }

    @Test
    void navigateToOnOpenGroundFindsARouteAndStartsWalking() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 1, 0, 0, 0));
        World world = flatWorld("world");

        CompletableFuture<Boolean> result = npc.navigateTo(new Location(world, 5, 1, 0), 4.0);

        assertTrue(result.isDone());
        assertTrue(result.join(), "a direct route across open ground must be found");
        assertTrue(npc.moving());
    }

    @Test
    void navigateToReturnsFalseWhenNoRouteExists() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 1, 0, 0, 0));
        World world = sealedWorld("world");

        CompletableFuture<Boolean> result = npc.navigateTo(new Location(world, 5, 1, 0), 4.0);

        assertFalse(result.join());
        assertFalse(npc.moving(), "a failed search must not start the NPC walking");
    }

    @Test
    void navigateToADifferentWorldFailsWithoutSearching() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 1, 0, 0, 0));
        World nether = flatWorld("nether");

        CompletableFuture<Boolean> result = npc.navigateTo(new Location(nether, 5, 1, 0), 4.0);

        assertFalse(result.join(), "cross-world routes are not supported");
        assertFalse(npc.moving());
    }

    @Test
    void stopWalkingCancelsAnInProgressRoute() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 1, 0, 0, 0));
        World world = flatWorld("world");
        npc.navigateTo(new Location(world, 5, 1, 0), 4.0).join();
        assertTrue(npc.moving());

        npc.stopWalking();

        assertFalse(npc.moving());
    }

    @Test
    void interactCarriesTheSneakingFlag() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        Player p = player(UUID.randomUUID());

        backend.fireInteract(p, npc.entityId(), ClickType.RIGHT, true);

        assertTrue(firstEvent(NpcInteractEvent.class).isSneaking());
    }

    @Test
    void autoRefreshResendsTheNametagOnItsInterval() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        npc.nametag(List.of("hi"));
        Player p = player(UUID.randomUUID());
        track(p, "world", 0, 64, 5);
        manager.tick();
        int before = backend.hologramRefreshes.size();

        npc.autoRefreshNametag(4); // every 4 ticks -> every 2 passes
        manager.tick();
        assertEquals(before, backend.hologramRefreshes.size(), "not yet due");
        manager.tick();
        assertEquals(before + 1, backend.hologramRefreshes.size(), "due on the second pass");
    }

    @Test
    void autoRefreshOffByDefault() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        npc.nametag(List.of("hi"));
        Player p = player(UUID.randomUUID());
        track(p, "world", 0, 64, 5);
        manager.tick();
        int before = backend.hologramRefreshes.size();

        manager.tick();
        manager.tick();

        assertEquals(before, backend.hologramRefreshes.size());
    }

    @Test
    void statsCountViewerShows() {
        NpcImpl a = manager.create("A", new Position("world", 0, 64, 0, 0, 0));
        NpcImpl b = manager.create("B", new Position("world", 0, 64, 0, 0, 0));
        track(player(UUID.randomUUID()), "world", 0, 64, 5);
        track(player(UUID.randomUUID()), "world", 0, 64, 5);
        manager.tick();

        assertEquals(2, manager.count());
        assertEquals(4, manager.viewerShows(), "2 NPCs x 2 viewers");
    }

    @Test
    void swingSendsAnAnimationToEveryViewer() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        Player a = player(UUID.randomUUID());
        Player b = player(UUID.randomUUID());
        track(a, "world", 0, 64, 5);
        track(b, "world", 0, 64, 5);
        manager.tick();

        npc.swing();

        assertEquals(2, backend.animations.size());
        assertEquals(0, backend.animations.get(0), "0 is swing main hand");
        npc.swingOffHand();
        assertEquals(3, backend.animations.get(2), "3 is swing off hand");
    }

    @Test
    void refreshNametagResendsTextWithoutRespawning() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        npc.nametag(List.of("hi %player%"));
        Player p = player(UUID.randomUUID());
        track(p, "world", 0, 64, 5);
        manager.tick();
        int showsBefore = backend.shows.size();

        npc.refreshNametag();

        assertEquals(1, backend.hologramRefreshes.size());
        assertEquals(showsBefore, backend.shows.size(), "a refresh must not respawn the NPC");
    }

    @Test
    void rawMetadataReachesTheSnapshotAndClears() {
        NpcImpl npc = manager.create(UUID.randomUUID(), "Zed", EntityType.ZOMBIE,
                new Position("world", 0, 64, 0, 0, 0));

        npc.metadata(16, net.folianpc.api.MetadataType.BOOLEAN, true); // e.g. baby zombie

        assertEquals(net.folianpc.api.MetadataType.BOOLEAN, npc.snapshot().rawMeta().get(16).type());
        assertEquals(true, npc.snapshot().rawMeta().get(16).value());

        npc.metadata(16, net.folianpc.api.MetadataType.BOOLEAN, null);

        assertTrue(npc.snapshot().rawMeta().isEmpty());
    }

    @Test
    void poseSurvivesIntoSnapshotAndData() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));

        npc.pose(NpcPose.SITTING);

        assertEquals(NpcPose.SITTING, npc.pose());
        assertEquals("SITTING", npc.snapshot().pose());
        assertEquals(NpcPose.SITTING, npc.data().pose());
    }

    @Test
    void quittingDropsVisibilityOverridesButRespawnDoesNot() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        UUID id = UUID.randomUUID();
        Player p = player(id);
        track(p, "world", 0, 64, 5);
        npc.hideFrom(id);

        manager.forgetPlayer(id); // a respawn: override must remain
        track(p, "world", 0, 64, 5);
        manager.tick();
        assertTrue(backend.shows.isEmpty(), "respawn keeps a standing hide");

        manager.dropPlayer(id);   // a quit: override is gone
        manager.tick();
        assertEquals(1, backend.shows.size(), "quit clears the override");
    }

    @Test
    void renamingResendsAndChangesTheWireName() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        Player p = player(UUID.randomUUID());
        track(p, "world", 0, 64, 5);
        manager.tick();
        int showsBefore = backend.shows.size();

        npc.name("Alice");

        assertEquals("Alice", npc.name());
        assertEquals("Alice", npc.profileName());
        assertEquals(showsBefore + 1, backend.shows.size());
    }

    @Test
    void perNpcViewDistanceOverridesTheGlobalOne() {
        manager.viewDistance(48);
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        npc.viewDistance(10);
        Player p = player(UUID.randomUUID());
        track(p, "world", 0, 64, 20); // inside the global range, outside this NPC's

        manager.tick();

        assertTrue(backend.shows.isEmpty());

        npc.viewDistance(0); // back to inheriting
        manager.tick();

        assertEquals(1, backend.shows.size());
    }

    @Test
    void quittingForgetsPerPlayerState() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        UUID id = UUID.randomUUID();
        Player p = player(id);
        track(p, "world", 0, 64, 5);
        npc.cooldown(10_000);
        manager.tick();
        backend.fireInteract(p, npc.entityId(), ClickType.RIGHT);
        assertTrue(npc.viewers().contains(id));

        manager.forgetPlayer(id);

        assertFalse(npc.viewers().contains(id));
        assertTrue(npc.allowInteract(id, 0L), "cooldown state is dropped with the player");
    }

    @Test
    void actionsRunInOrderForTheMatchingClickType() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        Player p = player(UUID.randomUUID());
        List<String> ran = new ArrayList<>();
        npc.addAction(ClickType.RIGHT, ctx -> ran.add("first"));
        npc.addAction(ClickType.RIGHT, ctx -> ran.add("second"));
        npc.addAction(ClickType.LEFT, ctx -> ran.add("left"));

        backend.fireInteract(p, npc.entityId(), ClickType.RIGHT);

        assertEquals(List.of("first", "second"), ran);
    }

    @Test
    void actionsAndTheClickListenerBothRun() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        Player p = player(UUID.randomUUID());
        List<String> ran = new ArrayList<>();
        npc.onClick((who, clicked, type) -> ran.add("listener"));
        npc.addAction(ClickType.LEFT, ctx -> ran.add("action"));

        backend.fireInteract(p, npc.entityId(), ClickType.LEFT);

        assertEquals(List.of("listener", "action"), ran);
    }

    @Test
    void cooldownBlocksRepeatClicksFromTheSamePlayer() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        AtomicLong now = new AtomicLong(1_000L);
        manager.clock(now::get);
        npc.cooldown(500L);
        Player p = player(UUID.randomUUID());
        AtomicInteger runs = new AtomicInteger();
        npc.addAction(ClickType.RIGHT, ctx -> runs.incrementAndGet());

        backend.fireInteract(p, npc.entityId(), ClickType.RIGHT);
        backend.fireInteract(p, npc.entityId(), ClickType.RIGHT);
        now.set(1_400L);
        backend.fireInteract(p, npc.entityId(), ClickType.RIGHT);

        assertEquals(1, runs.get(), "clicks inside the cooldown window are ignored");

        now.set(1_600L);
        backend.fireInteract(p, npc.entityId(), ClickType.RIGHT);

        assertEquals(2, runs.get(), "the click after the window runs");
    }

    @Test
    void cooldownIsPerPlayer() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        manager.clock(() -> 1_000L);
        npc.cooldown(500L);
        AtomicInteger runs = new AtomicInteger();
        npc.addAction(ClickType.RIGHT, ctx -> runs.incrementAndGet());

        backend.fireInteract(player(UUID.randomUUID()), npc.entityId(), ClickType.RIGHT);
        backend.fireInteract(player(UUID.randomUUID()), npc.entityId(), ClickType.RIGHT);

        assertEquals(2, runs.get(), "one player's cooldown must not block another");
    }

    @Test
    void cancelRemainingStopsLaterActions() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        Player p = player(UUID.randomUUID());
        List<String> ran = new ArrayList<>();
        npc.addAction(ClickType.RIGHT, ctx -> ran.add("first"));
        npc.addAction(ClickType.RIGHT, NpcClickContext::cancelRemaining);
        npc.addAction(ClickType.RIGHT, ctx -> ran.add("never"));

        backend.fireInteract(p, npc.entityId(), ClickType.RIGHT);

        assertEquals(List.of("first"), ran);
    }

    @Test
    void actionsSeeTheClickTypeAndShareContextData() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        Player p = player(UUID.randomUUID());
        AtomicReference<ClickType> seen = new AtomicReference<>();
        npc.addAction(ClickType.LEFT, ctx -> ctx.data().put("hits", 7));
        npc.addAction(ClickType.LEFT, ctx -> {
            seen.set(ctx.click());
            assertEquals(7, ctx.data().get("hits"));
        });

        backend.fireInteract(p, npc.entityId(), ClickType.LEFT);

        assertEquals(ClickType.LEFT, seen.get());
    }

    @Test
    void whenGatesAnActionAndThenChainsTwo() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        Player p = player(UUID.randomUUID());
        List<String> ran = new ArrayList<>();
        NpcAction blocked = ((NpcAction) ctx -> ran.add("blocked")).when(ctx -> false);
        NpcAction chained = ((NpcAction) ctx -> ran.add("a")).then(ctx -> ran.add("b"));
        npc.addAction(ClickType.RIGHT, blocked);
        npc.addAction(ClickType.RIGHT, chained);

        backend.fireInteract(p, npc.entityId(), ClickType.RIGHT);

        assertEquals(List.of("a", "b"), ran);
    }

    @Test
    void oneThrowingActionDoesNotStopTheRest() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        Player p = player(UUID.randomUUID());
        List<String> ran = new ArrayList<>();
        npc.addAction(ClickType.RIGHT, ctx -> {
            throw new IllegalStateException("developer bug");
        });
        npc.addAction(ClickType.RIGHT, ctx -> ran.add("still runs"));

        backend.fireInteract(p, npc.entityId(), ClickType.RIGHT);

        assertEquals(List.of("still runs"), ran);
    }

    @Test
    void clearingActionsStopsThemRunning() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        Player p = player(UUID.randomUUID());
        AtomicInteger runs = new AtomicInteger();
        npc.addAction(ClickType.RIGHT, ctx -> runs.incrementAndGet());

        npc.clearActions(ClickType.RIGHT);
        backend.fireInteract(p, npc.entityId(), ClickType.RIGHT);

        assertEquals(0, runs.get());
    }

    // ItemStack can't be built or mocked without a running server (org.bukkit.Registry), so these
    // cover the routing only; the item conversion itself lives in the NMS backend.
    @Test
    void equipmentChangePushesToViewersWithoutRespawning() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        Player p = player(UUID.randomUUID());
        track(p, "world", 0, 64, 5);
        manager.tick();
        int showsAfterSpawn = backend.shows.size();

        npc.equipment(EquipmentSlot.HAND, null);

        assertEquals(1, backend.equips.size());
        assertEquals(npc.entityId(), backend.equips.get(0).entityId());
        assertEquals(showsAfterSpawn, backend.shows.size(), "equipment must not force a respawn");
    }

    @Test
    void equipmentStartsEmptyAndClearingIsSafe() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));

        npc.equipment(EquipmentSlot.HEAD, null);

        assertTrue(npc.equipment().isEmpty());
        assertTrue(npc.snapshot().equipment().isEmpty());
        assertTrue(npc.data().equipment().isEmpty());
    }

    @Test
    void mobNpcsKeepTheirTypeThroughSnapshotAndData() {
        NpcImpl npc = manager.create(UUID.randomUUID(), "Zed", EntityType.ZOMBIE,
                new Position("world", 0, 64, 0, 0, 0));

        assertEquals(EntityType.ZOMBIE, npc.type());
        assertEquals(EntityType.ZOMBIE, npc.snapshot().type());
        assertFalse(npc.snapshot().isPlayer());
        assertEquals(EntityType.ZOMBIE, npc.data().type());
    }

    @Test
    void playerNpcsAreStillFlaggedAsPlayers() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));

        assertEquals(EntityType.PLAYER, npc.type());
        assertTrue(npc.snapshot().isPlayer());
    }

    @Test
    void ageableMobsAreFlaggedAgeable() {
        NpcImpl zombie = manager.create(UUID.randomUUID(), "Zed", EntityType.ZOMBIE,
                new Position("world", 0, 64, 0, 0, 0));
        NpcImpl villager = manager.create(UUID.randomUUID(), "Vil", EntityType.VILLAGER,
                new Position("world", 0, 64, 0, 0, 0));

        assertTrue(zombie.snapshot().isAgeable());
        assertTrue(villager.snapshot().isAgeable());
    }

    @Test
    void nonAgeableEntitiesAreNotFlaggedAgeable() {
        NpcImpl player = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        NpcImpl skeleton = manager.create(UUID.randomUUID(), "Bones", EntityType.SKELETON,
                new Position("world", 0, 64, 0, 0, 0));

        assertFalse(player.snapshot().isAgeable());
        assertFalse(skeleton.snapshot().isAgeable());
    }

    @Test
    void babyDefaultsFalseAndPushesMetadataWithoutRespawning() {
        NpcImpl npc = manager.create(UUID.randomUUID(), "Zed", EntityType.ZOMBIE,
                new Position("world", 0, 64, 0, 0, 0));
        Player p = player(UUID.randomUUID());
        track(p, "world", 0, 64, 5);
        manager.tick();
        int showsBefore = backend.shows.size();

        assertFalse(npc.baby());

        npc.baby(true);

        assertTrue(npc.baby());
        assertTrue(npc.snapshot().baby());
        assertEquals(1, backend.metas.size());
        assertTrue(backend.metas.get(0).baby());
        assertEquals(showsBefore, backend.shows.size(), "baby state must not force a respawn");
    }

    @Test
    void babyRoundTripsThroughData() {
        NpcImpl npc = manager.create(UUID.randomUUID(), "Zed", EntityType.ZOMBIE,
                new Position("world", 0, 64, 0, 0, 0));

        npc.baby(true);

        assertTrue(npc.data().baby());
    }

    @Test
    void showInTabListDefaultsFalseAndResendsWhenToggled() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        Player p = player(UUID.randomUUID());
        track(p, "world", 0, 64, 5);
        manager.tick();
        int showsBefore = backend.shows.size();

        assertFalse(npc.showInTabList());

        npc.showInTabList(true);

        assertTrue(npc.showInTabList());
        assertTrue(npc.snapshot().showInTabList());
        assertEquals(showsBefore + 1, backend.shows.size(), "the tab-list entry is part of the wire profile");
    }

    @Test
    void showInTabListRoundTripsThroughData() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));

        npc.showInTabList(true);

        assertTrue(npc.data().showInTabList());
    }

    @Test
    void mobVariantDefaultsToZeroWithNoNameSet() {
        NpcImpl npc = manager.create(UUID.randomUUID(), "Fluffy", EntityType.CAT,
                new Position("world", 0, 64, 0, 0, 0));

        assertEquals(0, npc.variant());
        assertNull(npc.variantName());
        assertNull(npc.villagerProfession());
        assertNull(npc.villagerType());
        assertEquals(1, npc.villagerLevel());
    }

    @Test
    void intAndNamedVariantsAreIndependent() {
        NpcImpl npc = manager.create(UUID.randomUUID(), "Rex", EntityType.RABBIT,
                new Position("world", 0, 64, 0, 0, 0));

        npc.variant(3);
        npc.variant("black");

        assertEquals(3, npc.variant(), "setting the named variant must not clear the int one");
        assertEquals("black", npc.variantName());
    }

    @Test
    void mobVariantPushesMetadataWithoutRespawning() {
        NpcImpl npc = manager.create(UUID.randomUUID(), "Rex", EntityType.RABBIT,
                new Position("world", 0, 64, 0, 0, 0));
        Player p = player(UUID.randomUUID());
        track(p, "world", 0, 64, 5);
        manager.tick();
        int showsBefore = backend.shows.size();

        npc.variant(2);

        assertEquals(1, backend.metas.size());
        assertEquals(2, backend.metas.get(0).mobVariant().variant());
        assertEquals(showsBefore, backend.shows.size(), "mob variant must not force a respawn");
    }

    @Test
    void villagerFieldsRoundTripThroughData() {
        NpcImpl npc = manager.create(UUID.randomUUID(), "Merchant", EntityType.VILLAGER,
                new Position("world", 0, 64, 0, 0, 0));

        npc.villagerProfession("farmer").villagerType("plains").villagerLevel(4);

        assertEquals("farmer", npc.data().mobVariant().villagerProfession());
        assertEquals("plains", npc.data().mobVariant().villagerType());
        assertEquals(4, npc.data().mobVariant().villagerLevel());
    }

    @Test
    void mobVariantBulkSetterReplacesEverythingAtOnce() {
        NpcImpl npc = manager.create(UUID.randomUUID(), "Merchant", EntityType.VILLAGER,
                new Position("world", 0, 64, 0, 0, 0));
        npc.villagerProfession("librarian");

        npc.mobVariant(new net.folianpc.api.MobVariant(0, null, "farmer", "desert", 2));

        assertEquals("farmer", npc.villagerProfession());
        assertEquals("desert", npc.villagerType());
        assertEquals(2, npc.villagerLevel());
    }

    @Test
    void copyPropagatesMobVariant() {
        NpcImpl original = manager.create(UUID.randomUUID(), "Merchant", EntityType.VILLAGER,
                new Position("world", 0, 64, 0, 0, 0));
        original.villagerProfession("librarian").villagerType("taiga").villagerLevel(5);

        Npc copy = original.copy(new org.bukkit.Location(null, 1, 64, 1));

        assertEquals("librarian", copy.villagerProfession());
        assertEquals("taiga", copy.villagerType());
        assertEquals(5, copy.villagerLevel());
    }

    @Test
    void changingTypeResendsTheNpcAsTheNewEntity() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        Player p = player(UUID.randomUUID());
        track(p, "world", 0, 64, 5);
        manager.tick();
        int showsBefore = backend.shows.size();

        npc.type(EntityType.ZOMBIE);

        assertEquals(EntityType.ZOMBIE, npc.type());
        assertEquals(EntityType.ZOMBIE, npc.snapshot().type());
        assertEquals(showsBefore + 1, backend.shows.size(), "the spawn packet carries the type, so it must be re-sent");
    }

    @Test
    void changingTypeToNullFallsBackToPlayer() {
        NpcImpl npc = manager.create(UUID.randomUUID(), "Zed", EntityType.ZOMBIE,
                new Position("world", 0, 64, 0, 0, 0));

        npc.type(null);

        assertEquals(EntityType.PLAYER, npc.type());
    }

    @Test
    void copyDuplicatesConfigurationAtANewLocationWithItsOwnId() {
        NpcImpl original = manager.create("Shopkeeper", new Position("world", 0, 64, 0, 0, 0));
        original.glowing(true).scale(1.5).cooldown(2000);
        original.nametag(List.of("hi"));
        original.baby(true).showInTabList(true);
        List<String> ran = new ArrayList<>();
        original.addAction(ClickType.RIGHT, ctx -> ran.add("original-action"));

        Npc copy = original.copy(new org.bukkit.Location(null, 10, 65, 10));

        assertNotEquals(original.id(), copy.id());
        assertEquals(original.name(), copy.name());
        assertEquals(10.0, copy.x());
        assertEquals(65.0, copy.y());
        assertEquals(10.0, copy.z());
        assertTrue(copy.glowing());
        assertEquals(1.5, copy.scale());
        assertEquals(2000L, copy.cooldown());
        assertEquals(List.of("hi"), copy.nametag());
        assertTrue(copy.baby());
        assertTrue(copy.showInTabList());

        Player p = player(UUID.randomUUID());
        backend.fireInteract(p, ((NpcImpl) copy).entityId(), ClickType.RIGHT);
        assertEquals(List.of("original-action"), ran, "the copy keeps the original's actions");
    }

    @Test
    void ownerRoundTripsThroughData() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        UUID creator = UUID.randomUUID();

        npc.owner(creator);

        assertEquals(creator, npc.owner());
        assertEquals(creator, npc.data().owner());
    }

    @Test
    void distantTrackedClicksAreIgnoredButActionsStillRunUpClose() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        Player near = player(UUID.randomUUID());
        Player far = player(UUID.randomUUID());
        track(near, "world", 0, 64, 5);
        track(far, "world", 0, 64, 500);
        AtomicInteger runs = new AtomicInteger();
        npc.addAction(ClickType.RIGHT, ctx -> runs.incrementAndGet());

        backend.fireInteract(far, npc.entityId(), ClickType.RIGHT);
        assertEquals(0, runs.get(), "a forged click from a known-distant tracked position must not run");

        backend.fireInteract(near, npc.entityId(), ClickType.RIGHT);
        assertEquals(1, runs.get(), "a click from a tracked nearby position runs normally");
    }

    @Test
    void untrackedClicksStillRunSincePositionIsUnknownRatherThanWrong() {
        NpcImpl npc = manager.create("Bob", new Position("world", 0, 64, 0, 0, 0));
        Player p = player(UUID.randomUUID()); // never tracked, e.g. the instant after join
        AtomicInteger runs = new AtomicInteger();
        npc.addAction(ClickType.RIGHT, ctx -> runs.incrementAndGet());

        backend.fireInteract(p, npc.entityId(), ClickType.RIGHT);

        assertEquals(1, runs.get());
    }

    @Test
    void dataSnapshotCarriesEverythingNeededToRespawn() {
        NpcImpl npc = manager.create("Bob", new Position("world", 1.5, 64, 2.5, 30, 10));
        npc.lookAtPlayers(true);
        npc.skin(new Skin("value", "signature"));

        NpcData data = npc.data();

        assertEquals(npc.id(), data.id());
        assertEquals("Bob", data.name());
        assertEquals("world", data.world());
        assertEquals(1.5, data.x());
        assertEquals(2.5, data.z());
        assertEquals(30f, data.yaw());
        assertTrue(data.lookAtPlayers());
        assertEquals("value", data.skin().value());
    }
}
