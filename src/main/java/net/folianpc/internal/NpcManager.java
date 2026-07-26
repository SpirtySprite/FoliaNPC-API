package net.folianpc.internal;

import net.folianpc.api.NpcClickListener;
import net.folianpc.api.event.NpcInteractEvent;
import net.folianpc.api.event.NpcRemoveEvent;
import net.folianpc.api.event.NpcSpawnEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import net.folianpc.internal.geometry.LookAt;
import net.folianpc.internal.pathfinding.RoutePlanner;
import net.folianpc.internal.protocol.NpcSnapshot;
import net.folianpc.internal.protocol.ProtocolBackend;
import net.folianpc.internal.scheduler.Schedulers;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class NpcManager {

    private final Plugin plugin;
    private final ProtocolBackend backend;
    private final PlayerTracker tracker;

    private final ConcurrentHashMap<UUID, NpcImpl> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, NpcImpl> byEntityId = new ConcurrentHashMap<>();

    private volatile double viewDistance = 48.0;
    private volatile RoutePlanner planner = new RoutePlanner();

    private static final double SECONDS_PER_PASS = 0.1;

    private final Set<UUID> shouldSee = new HashSet<>();
    private volatile boolean debug;

    private volatile java.util.function.LongSupplier clock = System::currentTimeMillis;
    private volatile Consumer<Event> events = event -> Bukkit.getPluginManager().callEvent(event);
    private volatile java.util.concurrent.Executor async = Runnable::run;

    public void async(java.util.concurrent.Executor executor) {
        this.async = executor;
    }

    public void clock(java.util.function.LongSupplier clock) {
        this.clock = clock;
    }

    public void events(Consumer<Event> publisher) {
        this.events = publisher;
    }

    public NpcManager(Plugin plugin, ProtocolBackend backend, PlayerTracker tracker) {
        this.plugin = plugin;
        this.backend = backend;
        this.tracker = tracker;
        backend.onInteract(this::handleInteract);
    }

    public void viewDistance(double blocks) {
        this.viewDistance = Math.max(1.0, blocks);
    }

    public void debug(boolean value) {
        this.debug = value;
    }

    public int count() {
        return byId.size();
    }

    private void log(String message) {
        if (debug && plugin != null && plugin.getLogger() != null) {
            plugin.getLogger().info("FoliaNPC: " + message);
        }
    }

    public NpcImpl create(String name, Position position) {
        return create(UUID.randomUUID(), name, org.bukkit.entity.EntityType.PLAYER, position);
    }

    public NpcImpl create(UUID id, String name, org.bukkit.entity.EntityType type, Position position) {
        NpcImpl npc = new NpcImpl(id, backend.nextEntityId(), name, type, position, this);
        byId.put(npc.id(), npc);
        byEntityId.put(npc.entityId(), npc);
        events.accept(new NpcSpawnEvent(npc));
        return npc;
    }

    public NpcImpl get(UUID id) {
        return byId.get(id);
    }

    public java.util.Collection<NpcImpl> all() {
        return byId.values();
    }

    void unregister(NpcImpl npc) {
        byId.remove(npc.id());
        byEntityId.remove(npc.entityId());
        for (UUID viewerId : npc.viewers()) {
            PlayerTracker.Tracked t = tracker.get(viewerId);
            if (t != null) {
                hideFrom(t.player(), npc);
            }
        }
        npc.viewers().clear();
        events.accept(new NpcRemoveEvent(npc));
    }

    int newEntityId() {
        return backend.nextEntityId();
    }

    public void forgetPlayer(UUID playerId) {
        for (NpcImpl npc : byId.values()) {
            npc.forget(playerId);
        }
    }

    private void hideFrom(Player viewer, NpcImpl npc) {
        NpcSnapshot snapshot = npc.snapshot();
        int[] lineIds = npc.nametagIds();
        Schedulers.onEntity(plugin, viewer, () -> {
            backend.hide(viewer, snapshot);
            if (lineIds.length > 0) {
                backend.removeEntities(viewer, lineIds);
            }
        });
    }

    public void refresh(NpcImpl npc, int[] staleLineIds) {
        forEachViewer(npc, (viewer, snapshot) -> {
            backend.removeEntities(viewer, staleLineIds);
            backend.hide(viewer, snapshot);
            backend.show(viewer, snapshot);
        });
    }

    public void updateMeta(NpcImpl npc) {
        forEachViewer(npc, backend::updateMeta);
    }

    public void updateScale(NpcImpl npc) {
        forEachViewer(npc, (viewer, npcState) -> backend.scale(viewer, npcState.entityId(), npcState.scale()));
    }

    public void updateEquipment(NpcImpl npc) {
        forEachViewer(npc, (viewer, npcState) ->
                backend.equip(viewer, npcState.entityId(), npcState.equipment()));
    }

    public void updateNametag(NpcImpl npc) {
        forEachViewer(npc, backend::refreshHologram);
    }

    public void animate(NpcImpl npc, int action) {
        forEachViewer(npc, (viewer, npcState) -> backend.animate(viewer, npcState.entityId(), action));
    }

    public void reposition(NpcImpl npc) {
        NpcSnapshot snapshot = npc.snapshot();
        double range = npc.viewDistance() > 0 ? npc.viewDistance() : viewDistance;
        double maxDistSq = range * range;
        int[] lines = npc.nametagIds();
        for (UUID viewerId : new java.util.ArrayList<>(npc.viewers())) {
            PlayerTracker.Tracked t = tracker.get(viewerId);
            if (t == null) {
                npc.viewers().remove(viewerId);
                continue;
            }
            boolean stillVisible = t.world().equals(snapshot.world())
                    && npc.visibleTo(viewerId, npc.position().distanceSquared(t.x(), t.y(), t.z()) <= maxDistSq);
            npc.forgetLook(viewerId);
            Player viewer = t.player();
            if (stillVisible) {
                Schedulers.onEntity(plugin, viewer, () -> {
                    backend.removeEntities(viewer, lines);
                    backend.hide(viewer, snapshot);
                    backend.show(viewer, snapshot);
                });
            } else {
                npc.viewers().remove(viewerId);
                Schedulers.onEntity(plugin, viewer, () -> {
                    backend.hide(viewer, snapshot);
                    backend.removeEntities(viewer, lines);
                });
            }
        }
    }

    // Global region thread can't read blocks on Folia; scheduling on the NPC's own location keeps it legal.
    public CompletableFuture<Boolean> navigate(NpcImpl npc, Location target, double blocksPerSecond) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        World world = target.getWorld();
        if (world == null || !world.getName().equals(npc.position().world())) {
            future.complete(false);
            return future;
        }
        Position from = npc.position();
        Location npcLocation = new Location(world, from.x(), from.y(), from.z());
        Schedulers.onRegion(plugin, npcLocation, () -> {
            List<double[]> route = planner.route(world, from.x(), from.y(), from.z(),
                    target.getX(), target.getY(), target.getZ());
            if (route.isEmpty()) {
                future.complete(false);
            } else {
                npc.followRoute(route, blocksPerSecond);
                future.complete(true);
            }
        });
        return future;
    }

    private void walkStep(NpcImpl npc, double[] delta) {
        int entityId = npc.entityId();
        int[] lines = npc.nametagIds();
        float yaw = npc.position().yaw();
        float pitch = npc.position().pitch();
        for (UUID viewerId : npc.viewers()) {
            PlayerTracker.Tracked t = tracker.get(viewerId);
            if (t == null) {
                continue;
            }
            Player viewer = t.player();
            Schedulers.onEntity(plugin, viewer, () -> {
                backend.move(viewer, entityId, delta[0], delta[1], delta[2]);
                for (int lineId : lines) {
                    backend.move(viewer, lineId, delta[0], delta[1], delta[2]);
                }
                if (npc.lookChanged(viewerId, yaw, pitch)) {
                    backend.look(viewer, entityId, yaw, pitch);
                }
            });
        }
    }

    public void dropPlayer(UUID playerId) {
        for (NpcImpl npc : byId.values()) {
            npc.forget(playerId);
            npc.dropVisibility(playerId);
        }
    }

    private void forEachViewer(NpcImpl npc, BiConsumer<Player, NpcSnapshot> action) {
        NpcSnapshot snapshot = npc.snapshot();
        for (UUID viewerId : npc.viewers()) {
            PlayerTracker.Tracked tracked = tracker.get(viewerId);
            if (tracked != null) {
                Player viewer = tracked.player();
                Schedulers.onEntity(plugin, viewer, () -> action.accept(viewer, snapshot));
            }
        }
    }

    public void tick() {
        long start = System.nanoTime();
        for (NpcImpl npc : byId.values()) {
            if (!npc.removed()) {
                tick(npc);
            }
        }
        lastTickMillis = (System.nanoTime() - start) / 1_000_000.0;
    }

    private volatile double lastTickMillis;

    public double lastTickMillis() {
        return lastTickMillis;
    }

    public int viewerShows() {
        int total = 0;
        for (NpcImpl npc : byId.values()) {
            total += npc.viewers().size();
        }
        return total;
    }

    private void tick(NpcImpl npc) {
        if (npc.moving()) {
            double[] delta = npc.stepWalk(SECONDS_PER_PASS);
            if (delta != null) {
                walkStep(npc, delta);
            }
        }
        if (npc.dueForNametagRefresh()) {
            updateNametag(npc);
        }
        Position pos = npc.position();
        double range = npc.viewDistance() > 0 ? npc.viewDistance() : viewDistance;
        double maxDistSq = range * range;
        double eyeY = pos.y() + Position.EYE_HEIGHT;
        NpcSnapshot snapshot = null;
        shouldSee.clear();

        for (PlayerTracker.Tracked t : tracker.all()) {
            if (!t.world().equals(pos.world())) {
                continue;
            }
            boolean inRange = pos.distanceSquared(t.x(), t.y(), t.z()) <= maxDistSq;
            if (!npc.visibleTo(t.uuid(), inRange)) {
                continue;
            }
            shouldSee.add(t.uuid());
            Player viewer = t.player();

            if (npc.viewers().add(t.uuid())) {
                if (snapshot == null) {
                    snapshot = npc.snapshot();
                }
                NpcSnapshot spawn = snapshot;
                Schedulers.onEntity(plugin, viewer, () -> backend.show(viewer, spawn));
                log("shown '" + npc.name() + "' (id=" + npc.entityId() + ") to " + viewer.getName());
            }

            if (npc.lookAtPlayers() && !npc.moving()) {
                LookAt.Rotation r = LookAt.face(pos.x(), eyeY, pos.z(),
                        t.x(), t.y() + Position.EYE_HEIGHT, t.z());
                if (npc.lookChanged(t.uuid(), r.yaw(), r.pitch())) {
                    int entityId = npc.entityId();
                    Schedulers.onEntity(plugin, viewer, () -> backend.look(viewer, entityId, r.yaw(), r.pitch()));
                }
            }
        }

        for (Iterator<UUID> it = npc.viewers().iterator(); it.hasNext(); ) {
            UUID viewerId = it.next();
            if (!shouldSee.contains(viewerId)) {
                it.remove();
                npc.forgetLook(viewerId);
                PlayerTracker.Tracked t = tracker.get(viewerId);
                if (t != null) {
                    hideFrom(t.player(), npc);
                }
            }
        }
    }

    // Generous over vanilla's ~6-block reach - not exact enforcement, just rejecting an obviously forged click.
    private static final double MAX_INTERACT_DISTANCE_SQUARED = 10.0 * 10.0;

    private boolean handleInteract(org.bukkit.entity.Player viewer, int entityId,
                                   net.folianpc.api.ClickType type, boolean sneaking) {
        NpcImpl npc = byEntityId.get(entityId);
        if (npc == null) {
            return false;
        }
        PlayerTracker.Tracked t = tracker.get(viewer.getUniqueId());
        if (t != null && (!t.world().equals(npc.position().world())
                || npc.position().distanceSquared(t.x(), t.y(), t.z()) > MAX_INTERACT_DISTANCE_SQUARED)) {
            return true;
        }
        if (!npc.allowInteract(viewer.getUniqueId(), clock.getAsLong())) {
            return true;
        }
        Schedulers.onEntity(plugin, viewer, () -> interact(viewer, npc, type, sneaking));
        return true;
    }

    private void interact(Player viewer, NpcImpl npc, net.folianpc.api.ClickType type, boolean sneaking) {
        NpcInteractEvent event = new NpcInteractEvent(viewer, npc, type, sneaking);
        events.accept(event);
        if (event.isCancelled()) {
            return;
        }
        NpcClickListener listener = npc.clickListener();
        if (listener != null) {
            guard(npc, () -> listener.onClick(viewer, npc, type));
        }
        runActions(viewer, npc, type, sneaking);
    }

    private void guard(NpcImpl npc, Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            if (plugin != null && plugin.getLogger() != null) {
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "FoliaNPC: click handler on '" + npc.name() + "' threw", t);
            }
        }
    }

    private void runActions(Player viewer, NpcImpl npc, net.folianpc.api.ClickType type, boolean sneaking) {
        var entries = npc.actions(type);
        if (entries.isEmpty()) {
            return;
        }
        ClickContext ctx = new ClickContext(plugin, viewer, npc, type, sneaking, async);
        for (NpcImpl.ActionEntry entry : entries) {
            if (ctx.remainingCancelled()) {
                return;
            }
            Schedulers.onEntityLater(plugin, viewer, () -> {
                if (!ctx.remainingCancelled()) {
                    guard(npc, () -> entry.action().run(ctx));
                }
            }, entry.delayTicks());
        }
    }

    public void close() {
        for (NpcImpl npc : byId.values()) {
            for (UUID viewerId : npc.viewers()) {
                PlayerTracker.Tracked t = tracker.get(viewerId);
                if (t != null) {
                    hideFrom(t.player(), npc);
                }
            }
            npc.viewers().clear();
        }
        byId.clear();
        byEntityId.clear();
    }
}
