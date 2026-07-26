package net.folianpc.internal;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Each player's last known location, snapshotted on their own thread. The NPC loop reads these
// instead of calling getLocation() on a foreign entity, which is illegal across regions on Folia.
public final class PlayerTracker {

    public record Tracked(Player player, String world, double x, double y, double z) {
        public UUID uuid() {
            return player.getUniqueId();
        }
    }

    private final ConcurrentHashMap<UUID, Tracked> tracked = new ConcurrentHashMap<>();

    // Must run on the player's own region thread.
    public void refresh(Player player) {
        Location loc = player.getLocation();
        String world = loc.getWorld() != null ? loc.getWorld().getName() : "";
        put(new Tracked(player, world, loc.getX(), loc.getY(), loc.getZ()));
    }

    public void put(Tracked t) {
        tracked.put(t.uuid(), t);
    }

    public void remove(UUID id) {
        tracked.remove(id);
    }

    public Tracked get(UUID id) {
        return tracked.get(id);
    }

    public Collection<Tracked> all() {
        return tracked.values();
    }

    public int size() {
        return tracked.size();
    }
}
