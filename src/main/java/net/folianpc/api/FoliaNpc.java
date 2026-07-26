package net.folianpc.api;

import net.folianpc.internal.NpcImpl;
import net.folianpc.internal.NpcManager;
import net.folianpc.internal.PlayerTracker;
import net.folianpc.internal.Position;
import net.folianpc.internal.protocol.nms.NmsProtocolBackend;
import net.folianpc.internal.scheduler.Schedulers;
import net.folianpc.internal.skin.MojangSkinService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

// Entry point. Create once in onEnable, close() in onDisable.
public final class FoliaNpc {

    private final Plugin plugin;
    private final NmsProtocolBackend backend;
    private final PlayerTracker tracker;
    private final NpcManager manager;
    private final MojangSkinService skins = new MojangSkinService();
    private final java.util.concurrent.ExecutorService async = java.util.concurrent.Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "folianpc-async");
        t.setDaemon(true);
        return t;
    });

    private Schedulers.Handle timer;
    private Listener listener;
    private boolean closed;

    private FoliaNpc(Plugin plugin, NmsProtocolBackend backend, PlayerTracker tracker, NpcManager manager) {
        this.plugin = plugin;
        this.backend = backend;
        this.tracker = tracker;
        this.manager = manager;
    }

    // Throws if the server is older than 1.20.6 or the packet layer can't bind.
    public static FoliaNpc create(Plugin plugin) {
        NmsProtocolBackend backend = new NmsProtocolBackend(plugin);
        PlayerTracker tracker = new PlayerTracker();
        NpcManager manager = new NpcManager(plugin, backend, tracker);
        FoliaNpc api = new FoliaNpc(plugin, backend, tracker, manager);
        manager.async(api.async);
        api.start();
        return api;
    }

    private void start() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            backend.injectViewer(p);
            Schedulers.onEntity(plugin, p, () -> tracker.refresh(p));
        }
        listener = new Listener() {
            @EventHandler
            public void onJoin(PlayerJoinEvent e) {
                Player p = e.getPlayer();
                backend.injectViewer(p);
                Schedulers.onEntity(plugin, p, () -> tracker.refresh(p));
            }

            @EventHandler
            public void onQuit(PlayerQuitEvent e) {
                tracker.remove(e.getPlayer().getUniqueId());
                manager.dropPlayer(e.getPlayer().getUniqueId());
                backend.ejectViewer(e.getPlayer());
            }

            @EventHandler
            public void onMove(PlayerMoveEvent e) {
                if (e.getTo() == null || e.getFrom().getBlockX() == e.getTo().getBlockX()
                        && e.getFrom().getBlockY() == e.getTo().getBlockY()
                        && e.getFrom().getBlockZ() == e.getTo().getBlockZ()) {
                    return;
                }
                tracker.refresh(e.getPlayer());
            }

            @EventHandler
            public void onWorld(PlayerChangedWorldEvent e) {
                tracker.refresh(e.getPlayer());
            }

            @EventHandler
            public void onRespawn(PlayerRespawnEvent e) {
                Player p = e.getPlayer();
                manager.forgetPlayer(p.getUniqueId());
                Schedulers.onEntity(plugin, p, () -> tracker.refresh(p));
            }
        };
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        timer = Schedulers.globalTimer(plugin, manager::tick, 2, 2);
    }

    public NpcBuilder builder() {
        return new NpcBuilder(this);
    }

    Npc spawn(NpcBuilder b) {
        Position pos = new Position(b.world, b.x, b.y, b.z, b.yaw, b.pitch);
        NpcImpl npc = manager.create(UUID.randomUUID(), b.name, b.type, pos);
        npc.lookAtPlayers(b.lookAtPlayers);
        if (b.listener != null) {
            npc.onClick(b.listener);
        }
        if (b.skin != null) {
            npc.skin(b.skin);
        }
        npc.mirrorSkin(b.mirrorSkin);
        npc.owner(b.owner);
        npc.showInTabList(b.showInTabList);
        b.equipment.forEach(npc::equipment);
        b.actions.forEach(e -> npc.addAction(e.getKey(), e.getValue()));
        npc.cooldown(b.cooldown);
        npc.viewDistance(b.viewDistance);
        npc.appearance(b.appearance);
        npc.pose(b.pose);
        npc.baby(b.baby);
        npc.mobVariant(b.mobVariant);
        if (!b.nametag.isEmpty()) {
            npc.nametag(b.nametag);
        }
        return npc;
    }

    public java.util.concurrent.CompletableFuture<Skin> fetchSkin(String playerName) {
        return skins.byName(playerName);
    }

    public java.util.concurrent.CompletableFuture<Skin> fetchSkin(UUID playerId) {
        return skins.byId(playerId);
    }

    public FoliaNpc skinCacheTtl(java.time.Duration ttl) {
        skins.ttl(ttl);
        return this;
    }

    public java.util.concurrent.CompletableFuture<Skin> fetchSkinFromUrl(String imageUrl) {
        return skins.byUrl(imageUrl);
    }

    public FoliaNpc placeholders(java.util.function.BiFunction<Player, String, String> resolver) {
        backend.nametagResolver(resolver);
        return this;
    }

    public Npc spawn(NpcData data) {
        UUID id = data.id() != null ? data.id() : UUID.randomUUID();
        Position pos = new Position(data.world(), data.x(), data.y(), data.z(), data.yaw(), data.pitch());
        org.bukkit.entity.EntityType type =
                data.type() != null ? data.type() : org.bukkit.entity.EntityType.PLAYER;
        NpcImpl npc = manager.create(id, data.name(), type, pos);
        npc.lookAtPlayers(data.lookAtPlayers());
        if (data.skin() != null) {
            npc.skin(data.skin());
        }
        npc.mirrorSkin(data.mirrorSkin());
        npc.owner(data.owner());
        npc.showInTabList(data.showInTabList());
        if (data.equipment() != null) {
            data.equipment().forEach(npc::equipment);
        }
        if (data.nametag() != null && !data.nametag().isEmpty()) {
            npc.nametag(data.nametag());
        }
        if (data.appearance() != null) {
            npc.appearance(data.appearance());
        }
        if (data.pose() != null) {
            npc.pose(data.pose());
        }
        npc.baby(data.baby());
        npc.mobVariant(data.mobVariant());
        return npc;
    }

    public java.util.List<Npc> spawnAll(java.util.Collection<NpcData> saved) {
        java.util.List<Npc> spawned = new java.util.ArrayList<>(saved.size());
        for (NpcData data : saved) {
            spawned.add(spawn(data));
        }
        return spawned;
    }

    public Npc get(UUID id) {
        return manager.get(id);
    }

    public java.util.List<Npc> all() {
        return java.util.List.copyOf(manager.all());
    }

    public FoliaNpc viewDistance(double blocks) {
        manager.viewDistance(blocks);
        return this;
    }

    public java.util.List<NpcData> saveAll() {
        java.util.List<NpcData> all = new java.util.ArrayList<>();
        for (Npc npc : manager.all()) {
            all.add(npc.data());
        }
        return all;
    }

    public void setDebug(boolean debug) {
        manager.debug(debug);
        backend.setDebug(debug);
    }

    public Capabilities capabilities() {
        return backend.capabilities();
    }

    public Stats stats() {
        return new Stats(manager.count(), manager.viewerShows(),
                backend.packetsSent(), manager.lastTickMillis());
    }

    public String diag() {
        return "npcs=" + manager.count() + " trackedPlayers=" + tracker.size();
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (timer != null) {
            timer.cancel();
        }
        if (listener != null) {
            HandlerList.unregisterAll(listener);
        }
        manager.close();
        for (Player p : Bukkit.getOnlinePlayers()) {
            backend.ejectViewer(p);
        }
        skins.close();
        async.shutdown();
    }
}
